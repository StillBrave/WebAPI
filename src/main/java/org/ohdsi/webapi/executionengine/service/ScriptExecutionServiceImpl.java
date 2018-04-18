package org.ohdsi.webapi.executionengine.service;

import com.odysseusinc.arachne.execution_engine_common.api.v1.dto.AnalysisRequestDTO;
import com.odysseusinc.arachne.execution_engine_common.api.v1.dto.AnalysisRequestStatusDTO;
import com.odysseusinc.arachne.execution_engine_common.api.v1.dto.AnalysisRequestTypeDTO;
import com.odysseusinc.arachne.execution_engine_common.api.v1.dto.DataSourceUnsecuredDTO;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import jersey.repackaged.com.google.common.collect.ImmutableMap;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.ohdsi.webapi.cohortcomparison.ComparativeCohortAnalysis;
import org.ohdsi.webapi.cohortcomparison.ComparativeCohortAnalysisExecutionRepository;
import org.ohdsi.webapi.cohortcomparison.ComparativeCohortAnalysisRepository;
import org.ohdsi.webapi.executionengine.dto.ExecutionRequestDTO;
import org.ohdsi.webapi.executionengine.entity.AnalysisExecution;
import org.ohdsi.webapi.executionengine.entity.AnalysisFile;
import org.ohdsi.webapi.executionengine.entity.AnalysisResultFile;
import org.ohdsi.webapi.executionengine.repository.AnalysisExecutionRepository;
import org.ohdsi.webapi.executionengine.repository.InputFileRepository;
import org.ohdsi.webapi.executionengine.repository.OutputFileRepository;
import org.ohdsi.webapi.executionengine.util.StringGenerationUtil;
import org.ohdsi.webapi.prediction.PatientLevelPredictionAnalysis;
import org.ohdsi.webapi.prediction.PatientLevelPredictionAnalysisRepository;
import org.ohdsi.webapi.source.Source;
import org.ohdsi.webapi.source.SourceDaimon;
import org.ohdsi.webapi.source.SourceRepository;
import org.ohdsi.webapi.util.DataSourceDTOParser;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("unused")
class ScriptExecutionServiceImpl implements ScriptExecutionService {

    private static final Log logger = LogFactory.getLog(ScriptExecutionServiceImpl.class);
    private static final String IMPALA_DATASOURCE = "impala";

    private final Client client = getClient();

    @Autowired
    ComparativeCohortAnalysisExecutionRepository comparativeCohortAnalysisExecutionRepository;
    @Value("${executionengine.url}")
    private String executionEngineURL;
    @Value("${executionengine.token}")
    private String executionEngineToken;
    @Value("${executionengine.resultCallback}")
    private String resultCallback;
    @Value("${executionengine.updateStatusCallback}")
    private String updateStatusCallback;
    @Value("${execution.invalidation.maxage}")
    private int invalidateHours;
    @Autowired
    private OutputFileRepository outputFileRepository;


    @Autowired
    private SourceRepository sourceRepository;
    @Autowired
    private InputFileRepository inputFileRepository;

    @Autowired
    private ComparativeCohortAnalysisRepository comparativeCohortAnalysisRepository;
    @Autowired
    private JobExplorer jobExplorer;
    @Autowired
    private AnalysisExecutionRepository analysisExecutionRepository;
    @Autowired
    private PatientLevelPredictionAnalysisRepository patientLevelPredictionAnalysisRepository;

    private List<AnalysisExecution.Status> INVALIDATE_STATUSES = new ArrayList<>();

    ScriptExecutionServiceImpl() throws KeyManagementException, NoSuchAlgorithmException {

        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        INVALIDATE_STATUSES.add(AnalysisExecution.Status.RUNNING);
        INVALIDATE_STATUSES.add(AnalysisExecution.Status.STARTED);
        INVALIDATE_STATUSES.add(AnalysisExecution.Status.PENDING);
    }

    @Override
    public Long runScript(ExecutionRequestDTO dto) throws Exception {

        Source source = sourceRepository.findBySourceKey(dto.sourceKey);
        final String cdmTableQualifier = source.getTableQualifier(SourceDaimon.DaimonType.CDM);
        final String resultsTableQualifier = source.getTableQualifier(SourceDaimon.DaimonType.Results);
        String vocabularyTableQualifier = source.getTableQualifierOrNull(SourceDaimon.DaimonType.Vocabulary);
        if (vocabularyTableQualifier == null) {
            vocabularyTableQualifier = cdmTableQualifier;
        }
        final String password = StringGenerationUtil.generateRandomString();

        AnalysisExecution execution = makeAnalysisExecution(dto, source, password);

        String name = getAnalysisName(dto);

        //replace var in R-script
        final String script = processTemplate(dto, cdmTableQualifier, resultsTableQualifier, vocabularyTableQualifier, source);
        AnalysisFile inputFile = new AnalysisFile();
        inputFile.setAnalysisExecution(execution);
        inputFile.setContents(script.getBytes());
        inputFile.setFileName(name + ".r");
        inputFileRepository.save(inputFile);

        WebTarget webTarget = client.target(executionEngineURL);
        MultiPart multiPart = buildRequest(buildAnalysisRequest(execution, source, password), script);
        try {
                webTarget
                    .request(MediaType.MULTIPART_FORM_DATA_TYPE)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", executionEngineToken)
                    .post(Entity.entity(multiPart, multiPart.getMediaType()),
                            AnalysisRequestStatusDTO.class);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            execution.setExecutionStatus(AnalysisExecution.Status.FAILED);
            analysisExecutionRepository.save(execution);
        }
        return execution.getId().longValue();
    }

    private MultiPart buildRequest(AnalysisRequestDTO analysisRequestDTO, String script) {

        MultiPart multiPart = new MultiPart();
        multiPart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);

        StreamDataBodyPart filePart = new StreamDataBodyPart("file",
                IOUtils.toInputStream(script),
                analysisRequestDTO.getExecutableFileName());
        multiPart.bodyPart(filePart);

        multiPart.bodyPart(
                new FormDataBodyPart("analysisRequest", analysisRequestDTO,
                        MediaType.APPLICATION_JSON_TYPE));
        return multiPart;
    }

    private AnalysisRequestDTO buildAnalysisRequest(AnalysisExecution execution, Source source, String password){
        AnalysisRequestDTO analysisRequestDTO = new AnalysisRequestDTO();
        Long executionId = execution.getId().longValue();
        analysisRequestDTO.setId(executionId);
        analysisRequestDTO.setDataSource(makeDataSourceDTO(source));
        analysisRequestDTO.setCallbackPassword(password);
        analysisRequestDTO.setRequested(new Date());
        String executableFileName = StringGenerationUtil.generateFileName(AnalysisRequestTypeDTO.R.name().toLowerCase());
        analysisRequestDTO.setExecutableFileName(executableFileName);
        analysisRequestDTO.setResultCallback(
                StrSubstitutor.replace(resultCallback,
                        ImmutableMap.of("id", executionId,
                                "password", password
                        ),
                        "{", "}"));
        analysisRequestDTO.setUpdateStatusCallback(
                StrSubstitutor.replace(updateStatusCallback,
                        ImmutableMap.of("id", executionId,
                                "password", password
                        ),
                        "{", "}"));
        return analysisRequestDTO;
    }

    private AnalysisExecution makeAnalysisExecution(ExecutionRequestDTO dto, Source source, String password) {

        AnalysisExecution execution = new AnalysisExecution();
        execution.setAnalysisId(dto.cohortId);
        execution.setAnalysisType(dto.analysisType);
        execution.setDuration(0);
        execution.setSourceId(source.getSourceId());
        execution.setExecuted(new Date());
        execution.setExecutionStatus(AnalysisExecution.Status.STARTED);
        execution.setUserId(0); //Looks strange
        execution.setUpdatePassword(password);
        analysisExecutionRepository.save(execution);
        return execution;
    }

    private String getAnalysisName(ExecutionRequestDTO dto) {

        String name;

        switch (dto.analysisType){
            case CCA:
                ComparativeCohortAnalysis cca = comparativeCohortAnalysisRepository.findOne(dto.cohortId);
                name = cca.getName();
                break;
            case PLP:
                PatientLevelPredictionAnalysis plp = patientLevelPredictionAnalysisRepository.findOne(dto.cohortId);
                name = plp.getName();
                break;
            default:
                name = "";
                break;
        }
        return name;
    }

    @Override
    public String getExecutionStatus(Long executionId) {

        String status;
        JobExecution execution = jobExplorer.getJobExecution(executionId);
        if (execution.getExecutionContext().containsKey("engineExecutionId")) {
            Long execId = execution.getExecutionContext().getLong("engineExecutionId");

            AnalysisExecution analysisExecution = analysisExecutionRepository.findOne(execId.intValue());
            if (analysisExecution == null) {
                throw new NotFoundException(String.format("Execution with id=%d was not found", executionId));
            }
            status = analysisExecution.getExecutionStatus().name();
        } else {
            status = AnalysisExecution.Status.PENDING.name();
        }
        return status;
    }

    @Override
    public List<AnalysisResultFile> getExecutionResultFiles(Long executionId) {

        return outputFileRepository.findByExecutionId(executionId.intValue());
    }

    @Scheduled(fixedDelayString = "${execution.invalidation.period}")
    public void invalidateExecutions(){

        Date invalidate = DateUtils.addHours(new Date(), -invalidateHours);
        List<AnalysisExecution> executions = analysisExecutionRepository.findByExecutedBeforeAndExecutionStatusIn(invalidate, INVALIDATE_STATUSES);
        executions.forEach(exec -> {
            exec.setExecutionStatus(AnalysisExecution.Status.FAILED);
            analysisExecutionRepository.save(exec);
        });
    }

    private String processTemplate(ExecutionRequestDTO requestDTO,
                                   String cdmTable, String resultsTable,
                                   String vocabularyTable,
                                   Source source) {

        ConnectionParams connectionParams = getConnectionParams(source.getSourceConnection());

        String temp = requestDTO.template
                .replace("dbms = \"postgresql\"", "dbms = \"" + connectionParams.getDbms() + "\"")
                .replace("server = \"localhost/ohdsi\"", "server = \"" + connectionParams.getServer() + "\"")
                .replace("user = \"joe\"", "user = \"" + connectionParams.getUser() + "\"")
                .replace("my_cdm_data", cdmTable)
                .replace("my_vocabulary_data", vocabularyTable)
                .replace("my_results", resultsTable)
                .replace("exposure_database_schema", cdmTable)
                .replace("outcome_database_schema", resultsTable)
                .replace("exposure_table", "cohort")
                .replace("outcome_table", "cohort")
                .replace("cohort_table", "cohort")
//                .replace("exposureTable <- \"exposure_table\"", "")
//                .replace("outcomeTable <- \"outcome_table\"", "")

                .replace("cdmVersion <- \"5\"",
                        "cdmVersion <- \"" + requestDTO.cdmVersion + "\"")
                .replace("<insert your " + "directory here>",
                        requestDTO.workFolder);
        if (IMPALA_DATASOURCE.equalsIgnoreCase(source.getSourceDialect())){
            temp = temp.replace("password = \"supersecret\"", "password = \""
                    + connectionParams.getPassword() + "\", "
                    + "schema=\"" + connectionParams.getSchema() + "\", "
                    + "extraSettings=\"" + connectionParams.getExtraSettings() + "\", "
                    + "pathToDriver=\"/impala/\"");

        } else {
            temp = temp.replace("password = \"supersecret\"", "password = \"" + connectionParams.getPassword() + "\"");
        }

        if (connectionParams.getPort() != null) {
            temp = temp.replace("port = 5432", "port = " + connectionParams.getPort());
        } else {
            temp = temp.replace( "port = 5432,", "");
        }
        //uncommenting package installation
        return temp
                .replace("true", "TRUE")
                .replace("false", "FALSE");
    }

    private ConnectionParams getConnectionParams(final String sourceConnection) {

        ConnectionParams connectionParams = new ConnectionParams();
        String tmp = sourceConnection;
        tmp = tmp.substring(tmp.indexOf(":") + 1);
        connectionParams.setDbms(tmp.substring(0, tmp.indexOf(":")));
        tmp = tmp.substring(tmp.indexOf(":") + 3);
        String serverPortDB = tmp.indexOf("?") > 0 ? tmp.substring(0, tmp.indexOf("?")) :
                tmp.indexOf(";") > 0 ? tmp.substring(0, tmp.indexOf(";")) : tmp;
        String port = serverPortDB.substring(serverPortDB.indexOf(":") + 1, serverPortDB.indexOf("/"));

        if (StringUtils.isNotBlank(port)) {
            connectionParams.setPort(port);
        }

        if (IMPALA_DATASOURCE.equalsIgnoreCase(connectionParams.getDbms())){
            int colonIndex = serverPortDB.indexOf(":");
            connectionParams.setServer(serverPortDB.substring(0, colonIndex));
            connectionParams.setSchema(serverPortDB.substring(serverPortDB.indexOf("/") + 1));
            connectionParams.setExtraSettings(tmp.substring(tmp.indexOf(";") + 1));
        } else {
            connectionParams.setServer(serverPortDB.substring(0, serverPortDB.indexOf(":")) + serverPortDB.substring(
                    serverPortDB.indexOf("/")));
        }
        tmp = tmp.substring(tmp.indexOf("?") + 1);
        String[] split = tmp.split("&");
        for (String s : split) {
            if (s.contains("user")) {
                connectionParams.setUser(s.substring(s.indexOf("=") + 1));
            }
            if (s.contains("password")) {
                connectionParams.setPassword(s.substring(s.indexOf("=") + 1));
            }
        }

        return connectionParams;
    }

    private DataSourceUnsecuredDTO makeDataSourceDTO(Source source) {

        DataSourceUnsecuredDTO dto = DataSourceDTOParser.parseDTO(source);
        dto.setCdmSchema(source.getTableQualifier(SourceDaimon.DaimonType.CDM));
        return dto;
    }

    private Client getClient() throws NoSuchAlgorithmException, KeyManagementException {

        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {

                        return null;
                    }

                    @Override
                    public void checkClientTrusted(
                            java.security.cert.X509Certificate[] certs, String authType) {

                    }

                    @Override
                    public void checkServerTrusted(
                            java.security.cert.X509Certificate[] certs, String authType) {

                    }
                }
        };
        HostnameVerifier hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();


        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustAllCerts, null);

        return ClientBuilder.newBuilder()
                .sslContext(sslContext)
                .register(MultiPartFeature.class)
                .build();
    }

    private class ConnectionParams {
        private String dbms;
        private String server;
        private String user;
        private String password;
        private String port;
        private String schema;
        private String extraSettings;

        String getDbms() {

            return dbms;
        }

        void setDbms(String dbms) {

            this.dbms = dbms;
        }

        String getServer() {

            return server;
        }

        void setServer(String server) {

            this.server = server;
        }

        String getUser() {

            return user;
        }

        void setUser(String user) {

            this.user = user;
        }

        String getPassword() {

            return password;
        }

        void setPassword(String password) {

            this.password = password;
        }

        public String getPort() {

            return port;
        }

        public void setPort(String port) {

            this.port = port;
        }

        public String getSchema() {

            return schema;
        }

        public void setSchema(String schema) {

            this.schema = schema;
        }

        public String getExtraSettings() {

            return extraSettings;
        }

        public void setExtraSettings(String extraSettings) {

            this.extraSettings = extraSettings;
        }
    }
}