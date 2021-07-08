package com.capitalone.dashboard.service;

import com.capitalone.dashboard.misc.HygieiaException;
import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.model.quality.CucumberJsonReport;
import com.capitalone.dashboard.model.quality.JunitXmlReport;
import com.capitalone.dashboard.model.quality.JunitXmlReportV2;
import com.capitalone.dashboard.repository.BuildRepository;
import com.capitalone.dashboard.repository.CollectorItemRepository;
import com.capitalone.dashboard.repository.CollectorRepository;
import com.capitalone.dashboard.repository.ComponentRepository;
import com.capitalone.dashboard.repository.TestResultRepository;
import com.capitalone.dashboard.request.CollectorRequest;
import com.capitalone.dashboard.request.PerfTestDataCreateRequest;
import com.capitalone.dashboard.request.TestDataCreateRequest;
import com.capitalone.dashboard.settings.ApiSettings;
import com.capitalone.dashboard.util.HygieiaUtils;
import com.capitalone.dashboard.util.TestResultConstants;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.querydsl.core.BooleanBuilder;
import hygieia.transformer.CucumberJsonToTestCapabilityTransformer;
import hygieia.transformer.JunitXmlToTestCapabilityTransformer;
import hygieia.transformer.JunitXmlToTestCapabilityTransformerV2;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.bson.types.ObjectId;
import org.joda.time.format.DateTimeFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class TestResultServiceImpl implements TestResultService {

    private final TestResultRepository testResultRepository;
    private final ComponentRepository componentRepository;
    private final CollectorRepository collectorRepository;
    private final CollectorItemRepository collectorItemRepository;
    private final BuildRepository buildRepository;
    private final CollectorService collectorService;
    private final CmdbService cmdbService;
    private final ApiSettings apiSettings;
    private  Path fileStorageLocation;

    private static final Logger LOGGER = Logger.getLogger(ApiTokenServiceImpl.class);

    @Autowired
    public TestResultServiceImpl(TestResultRepository testResultRepository,
                                 ComponentRepository componentRepository,
                                 CollectorRepository collectorRepository,
                                 BuildRepository buildRepository,
                                 CollectorItemRepository collectorItemRepository,
                                 CollectorService collectorService,
                                 CmdbService cmdbService,
                                 ApiSettings apiSettings) {
        this.testResultRepository = testResultRepository;
        this.componentRepository = componentRepository;
        this.collectorRepository = collectorRepository;
        this.buildRepository = buildRepository;
        this.collectorItemRepository = collectorItemRepository;
        this.collectorService = collectorService;
        this.cmdbService = cmdbService;
        this.apiSettings = apiSettings;
    }

    @Override
    public DataResponse<Iterable<TestResult>> search(com.capitalone.dashboard.request.TestResultRequest request) {
        Component component = componentRepository.findOne(request.getComponentId());

        if ((component == null) || !component.getCollectorItems().containsKey(CollectorType.Test)) {
            return new DataResponse<>(null, 0L);
        }
        List<TestResult> result = new ArrayList<>();
        validateAllCollectorItems(request, component, result);
        //One collector per Type. get(0) is hardcoded.
        if (!CollectionUtils.isEmpty(component.getCollectorItems().get(CollectorType.Test)) && (component.getCollectorItems().get(CollectorType.Test).get(0) != null)) {
            Collector collector = collectorRepository.findOne(component.getCollectorItems().get(CollectorType.Test).get(0).getCollectorId());
            if (collector != null) {
                return new DataResponse<>(pruneToDepth(result, request.getDepth()), collector.getLastExecuted());
            }
        }

        return new DataResponse<>(null, 0L);
    }



    private void validateAllCollectorItems(com.capitalone.dashboard.request.TestResultRequest request, Component component, List<TestResult> result) {
        // add all test result repos
        component.getCollectorItems().get(CollectorType.Test).forEach(item -> {
            QTestResult testResult = new QTestResult("testResult");
            BooleanBuilder builder = new BooleanBuilder();
            builder.and(testResult.collectorItemId.eq(item.getId()));
            validateStartDateRange(request, testResult, builder);
            validateEndDateRange(request, testResult, builder);
            validateDurationRange(request, testResult, builder);
            validateTestCapabilities(request, testResult, builder);
            addAllTestResultRepositories(request, result, testResult, builder);
        });
    }

    private void addAllTestResultRepositories(com.capitalone.dashboard.request.TestResultRequest request, List<TestResult> result, QTestResult testResult, BooleanBuilder builder) {
        if (request.getMax() == null) {
            result.addAll(Lists.newArrayList(testResultRepository.findAll(builder.getValue(), testResult.timestamp.desc())));
        } else {
            PageRequest pageRequest = new PageRequest(0, request.getMax(), Sort.Direction.DESC, "timestamp");
            result.addAll(Lists.newArrayList(testResultRepository.findAll(builder.getValue(), pageRequest).getContent()));
        }
    }

    private void validateTestCapabilities(com.capitalone.dashboard.request.TestResultRequest request, QTestResult testResult, BooleanBuilder builder) {
        if (!request.getTypes().isEmpty()) {
            builder.and(testResult.testCapabilities.any().type.in(request.getTypes()));
        }
    }

    private void validateDurationRange(com.capitalone.dashboard.request.TestResultRequest request, QTestResult testResult, BooleanBuilder builder) {
        if (request.validDurationRange()) {
            builder.and(testResult.duration.between(request.getDurationGreaterThan(), request.getDurationLessThan()));
        }
    }

    private void validateEndDateRange(com.capitalone.dashboard.request.TestResultRequest request, QTestResult testResult, BooleanBuilder builder) {
        if (request.validEndDateRange()) {
            builder.and(testResult.endTime.between(request.getEndDateBegins(), request.getEndDateEnds()));
        }
    }

    private void validateStartDateRange(com.capitalone.dashboard.request.TestResultRequest request, QTestResult testResult, BooleanBuilder builder) {
        if (request.validStartDateRange()) {
            builder.and(testResult.startTime.between(request.getStartDateBegins(), request.getStartDateEnds()));
        }
    }

    private Iterable<TestResult> pruneToDepth(List<TestResult> results, Integer depth) {
        // Prune the response to the requested depth
        // 0 - TestResult
        // 1 - TestCapability
        // 2 - TestSuite
        // 3 - TestCase
        // 4 - Entire response
        // null - Entire response
        if (depth == null || depth > 3) {
            return results;
        }
        results.forEach(result -> {
            if (depth == 0) {
                result.getTestCapabilities().clear();
            } else {
                result.getTestCapabilities().forEach(testCapability -> {
                    if (depth == 1) {
                        testCapability.getTestSuites().clear();
                    } else {
                        testCapability.getTestSuites().forEach(testSuite -> {
                            if (depth == 2) {
                                testSuite.getTestCases().clear();
                            } else { // depth == 3
                                testSuite.getTestCases().forEach(testCase -> {
                                    testCase.getTestSteps().clear();
                                });
                            }
                        });
                    }
                });
            }
        });

        return results;
    }


    protected TestResult createTest(TestDataCreateRequest request) throws HygieiaException {
        /*
          Step 1: create Collector if not there
          Step 2: create Collector item if not there
          Step 3: Insert test data if new. If existing, update it
         */
        Collector collector = createCollector();

        if (collector == null) {
            throw new HygieiaException("Failed creating Test collector.", HygieiaException.COLLECTOR_CREATE_ERROR);
        }

        CollectorItem collectorItem = createCollectorItem(collector, request);

        if (collectorItem == null) {
            throw new HygieiaException("Failed creating Test collector item.", HygieiaException.COLLECTOR_ITEM_CREATE_ERROR);
        }

        TestResult testResult = createTest(collectorItem, request);

        if (testResult == null) {
            throw new HygieiaException("Failed inserting/updating Test information.", HygieiaException.ERROR_INSERTING_DATA);
        }

        return testResult;

    }

    @Override
    public String create(TestDataCreateRequest request) throws HygieiaException {
        TestResult testResult = createTest(request);
        return testResult.getId().toString();
    }

    @Override
    public String createV2(TestDataCreateRequest request) throws HygieiaException {
        TestResult testResult = createTest(request);
        return testResult.getId().toString() + "," + testResult.getCollectorItemId().toString();
    }



    protected TestResult createPerfTest(PerfTestDataCreateRequest request) throws HygieiaException {
        /**
         * Step 1: create performance Collector if not there
         * Step 2: create Perfomance Collector item if not there
         * Step 3: Insert performance test data if new. If existing, update it
         */
        Collector collector = createGenericCollector(request.getPerfTool());
        if (collector == null) {
            throw new HygieiaException("Failed creating Test collector.", HygieiaException.COLLECTOR_CREATE_ERROR);
        }
        CollectorItem collectorItem = createGenericCollectorItem(collector, request);
        if (collectorItem == null) {
            throw new HygieiaException("Failed creating Test collector item.", HygieiaException.COLLECTOR_ITEM_CREATE_ERROR);
        }
        TestResult testResult = createPerfTest(collectorItem, request);
        if (testResult == null) {
            throw new HygieiaException("Failed inserting/updating Test information.", HygieiaException.ERROR_INSERTING_DATA);
        }
        return testResult;

    }

    protected List<TestResult> createTestCucumber(TestCreateRequest request) throws HygieiaException {

        CucumberJsonReport.Feature cucumberFeature = null;

        try {
            cucumberFeature = decodeJsonPayload(CucumberJsonReport.Feature.class , request);
            if(cucumberFeature.getId() == null || cucumberFeature.getKeyword() == null || cucumberFeature.getName() == null || cucumberFeature.getElements() == null) {
                throw new HygieiaException("TestResult is not a valid json.", HygieiaException.JSON_FORMAT_ERROR);
            }
        }catch (Exception ex){
            throw new HygieiaException("TestResult is not a valid json.", HygieiaException.JSON_FORMAT_ERROR);
        }
        Collector collector = createGenericCollector(request, TestResultConstants.JENKINSCUCUMBERTEST);
        if (collector == null) {
            throw new HygieiaException("Failed creating Test collector.", HygieiaException.COLLECTOR_CREATE_ERROR);
        }
        CollectorItem collectorItem = createGenericCollectorItem(collector, request , cucumberFeature.getName());
        if (collectorItem == null) {
            throw new HygieiaException("Failed creating Test collector item.", HygieiaException.COLLECTOR_ITEM_CREATE_ERROR);
        }
        List<CucumberJsonReport.Feature> features = new ArrayList<>();
        features.add(cucumberFeature);
        CucumberJsonReport cucumberRequest = new CucumberJsonReport(features);
        CucumberJsonToTestCapabilityTransformer transformer = new CucumberJsonToTestCapabilityTransformer(null,"");
        TestCapability testCapability = transformer.convert(cucumberRequest);
        TestResult testResult = createTestCucumber(collectorItem,cucumberFeature , TestSuiteType.Functional, request, testCapability);
        if (testResult == null) {
            throw new HygieiaException("Failed inserting cucumber Test information.", HygieiaException.ERROR_INSERTING_DATA);
        }

        List<TestResult> testResults = new ArrayList<>();
        testResults.add(testResult);
        return testResults;

    }

    protected List<TestResult> processTestJunit(TestCreateRequest request) throws HygieiaException {
        if(request == null || StringUtils.isEmpty(request.getTestResult())) {
            throw new HygieiaException("TestResult is not a valid Xml", HygieiaException.JSON_FORMAT_ERROR);
        }
        JunitXmlReport junitXmlReport = decodeXmlPayload(JunitXmlReport.class, request);
        JunitXmlReportV2 junitXmlReportV2;
        List<TestResult> testResults = new ArrayList<>();
        if (junitXmlReport != null) {
            testResults.add(createTestJunit(request, junitXmlReport));
            return testResults;
        } else {
            junitXmlReportV2 = decodeXmlPayload(JunitXmlReportV2.class, request);
            if (junitXmlReportV2 == null) {
                throw new HygieiaException("TestResult is not a valid Xml", HygieiaException.JSON_FORMAT_ERROR);
            }
        }

        List<JunitXmlReportV2.TestSuite> testSuites = junitXmlReportV2.getTestsuite();
        for (JunitXmlReportV2.TestSuite testSuite : testSuites) {
            testResults.add(createTestJunitV2(request, testSuite));
        }
        return testResults;
    }

        protected TestResult createTestJunit(TestCreateRequest request, JunitXmlReport junitXmlReport) throws HygieiaException {
        Collector collector = createGenericCollector(request, TestResultConstants.JUNITTEST);;
        if (collector == null) {
            throw new HygieiaException("Failed creating Test collector.", HygieiaException.COLLECTOR_CREATE_ERROR);
        }
        CollectorItem collectorItem = createGenericCollectorItem(collector, request , junitXmlReport.getName() );
        if (collectorItem == null) {
            throw new HygieiaException("Failed creating Test collector item.", HygieiaException.COLLECTOR_ITEM_CREATE_ERROR);
        }
        JunitXmlToTestCapabilityTransformer transformer = new JunitXmlToTestCapabilityTransformer();
        TestCapability testCapability = transformer.convert(junitXmlReport);
        TestResult testResult = createTestJunit(collectorItem, junitXmlReport,  TestSuiteType.Unit, request,testCapability);
        if (testResult == null) {
            throw new HygieiaException("Failed inserting Junit Test information.", HygieiaException.ERROR_INSERTING_DATA);
        }
        return testResult;

    }

    protected TestResult createTestJunitV2(TestCreateRequest request, JunitXmlReportV2.TestSuite junitXmlReportV2Testsuite) throws HygieiaException {
        Collector collector = createGenericCollector(request, TestResultConstants.JUNITTEST);
        if (collector == null) {
            throw new HygieiaException("Failed creating Test collector.", HygieiaException.COLLECTOR_CREATE_ERROR);
        }
        CollectorItem collectorItem = createGenericCollectorItem(collector, request , junitXmlReportV2Testsuite.getName());
        if (collectorItem == null) {
            throw new HygieiaException("Failed creating Test collector item.", HygieiaException.COLLECTOR_ITEM_CREATE_ERROR);
        }
        JunitXmlToTestCapabilityTransformerV2 transformer = new JunitXmlToTestCapabilityTransformerV2();
        TestCapability testCapability = transformer.convert(junitXmlReportV2Testsuite);
        TestResult testResult = createTestJunitV2(collectorItem, junitXmlReportV2Testsuite,  TestSuiteType.Unit, request, testCapability);
        if (testResult == null) {
            throw new HygieiaException("Failed inserting Junit Test information.", HygieiaException.ERROR_INSERTING_DATA);
        }
        return testResult;

    }

    private <T> T decodeJsonPayload (Class<T> type , TestCreateRequest request) throws HygieiaException{
        if(request == null || StringUtils.isEmpty(request.getTestResult())) {
            throw new HygieiaException("TestResult is not a valid json.", HygieiaException.JSON_FORMAT_ERROR);
        }
        byte[] decodedBytes = Base64.getDecoder().decode(request.getTestResult());
        String decodedPayload = new String(decodedBytes);
        Gson gson = new Gson();
        return gson.fromJson(decodedPayload , type);

    }

    private <T> T decodeXmlPayload (Class<T> type , TestCreateRequest request) {
        byte[] decodedBytes = Base64.getDecoder().decode(request.getTestResult());
        String decodedPayload = new String(decodedBytes);
        StringReader sr = new StringReader(decodedPayload);
        T junitXmlReport = null;
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(type);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            junitXmlReport = (T) unmarshaller.unmarshal(sr);
            LOGGER.info("Successful transformation to " + type + " xml format type");
        }catch (JAXBException ex){
            LOGGER.info("Could not transform to " + type + " xml format type: " + ex.toString());
        }
        return junitXmlReport;
    }

    @Override
    public String createTest(TestCreateRequest request) throws HygieiaException {
        List<TestResult> testResults = null;
        String response = "";
        validConfigurationItem(request.getConfigurationItem(),request.getTargetAppName());
        if (TestResultConstants.FUNCTIONAL.equals(request.getTestType()) && apiSettings.getFunctional().get("cucumber").equals(request.getSourceFormat())) {

            testResults = createTestCucumber(request);

        } else if (TestResultConstants.UNIT.equals(request.getTestType())&& apiSettings.getUnit().equals(request.getSourceFormat())) {

            testResults = processTestJunit(request);
        } else {
            return "Hygieia does not support " + request.getTestType() + " sourceFormat " + request.getSourceFormat();
        }
        for (TestResult testResult : testResults) {
            response += testResult.getId() + ", " + testResult.getCollectorItemId() + ";";
        }
        return response;
    }


    @Override
    public String createPerf(PerfTestDataCreateRequest request) throws HygieiaException {
        TestResult testResult = createPerfTest(request);
        return testResult.getId().toString();
    }

    @Override
    public String createPerfV2(PerfTestDataCreateRequest request) throws HygieiaException {
        TestResult testResult = createPerfTest(request);
        return testResult.getId().toString() + "," + testResult.getCollectorItemId().toString();
    }

    private Collector createCollector() {
        CollectorRequest collectorReq = new CollectorRequest();
        collectorReq.setName("JenkinsCucumberTest");
        collectorReq.setCollectorType(CollectorType.Test);
        Collector col = collectorReq.toCollector();
        col.setEnabled(true);
        col.setOnline(true);
        col.setLastExecuted(System.currentTimeMillis());
        Map<String, Object> allOptions = new HashMap<>();
        allOptions.put("jobUrl", "");
        allOptions.put("instanceUrl", "");
        allOptions.put("jobName","");
        allOptions.put("testType","");
        col.setAllFields(allOptions);
        //Combination of jobName and jobUrl should be unique always.
        Map<String, Object> uniqueOptions = new HashMap<>();
        uniqueOptions.put("jobUrl", "");
        uniqueOptions.put("jobName","");
        uniqueOptions.put("testType","");
        col.setUniqueFields(uniqueOptions);
        return collectorService.createCollector(col);
    }


    private Collector createGenericCollector(String performanceTool) {
        CollectorRequest collectorReq = new CollectorRequest();
        collectorReq.setName(performanceTool);
        collectorReq.setCollectorType(CollectorType.Test);
        Collector col = collectorReq.toCollector();
        col.setEnabled(true);
        col.setOnline(true);
        col.setLastExecuted(System.currentTimeMillis());
        Map<String, Object> allOptions = new HashMap<>();
        allOptions.put("jobName","");
        allOptions.put("instanceUrl", "");
        allOptions.put("testType","");
        col.setAllFields(allOptions);
        col.setUniqueFields(allOptions);
        return collectorService.createCollector(col);
    }

    private CollectorItem createCollectorItem(Collector collector, TestDataCreateRequest request) throws HygieiaException {
        CollectorItem tempCi = new CollectorItem();
        tempCi.setCollectorId(collector.getId());
        tempCi.setDescription(request.getDescription());
        tempCi.setPushed(true);
        tempCi.setLastUpdated(System.currentTimeMillis());
        Map<String, Object> option = new HashMap<>();
        option.put("jobName", request.getTestJobName());
        option.put("jobUrl", request.getTestJobUrl());
        option.put("instanceUrl", request.getServerUrl());
        option.put("testType",request.getType());
        tempCi.getOptions().putAll(option);
        tempCi.setNiceName(request.getNiceName());
        if (StringUtils.isEmpty(tempCi.getNiceName())) {
            return collectorService.createCollectorItem(tempCi);
        }
        return collectorService.createCollectorItemByNiceNameAndJobName(tempCi, request.getTestJobName());
    }


    private CollectorItem createGenericCollectorItem(Collector collector, PerfTestDataCreateRequest request) {
        CollectorItem tempCi = new CollectorItem();
        tempCi.setCollectorId(collector.getId());
        tempCi.setDescription(request.getPerfTool()+" : "+request.getTestName());
        tempCi.setPushed(true);
        tempCi.setLastUpdated(System.currentTimeMillis());
        Map<String, Object> option = new HashMap<>();
        option.put("jobName", request.getTestName());
        option.put("instanceUrl", request.getInstanceUrl());
        option.put("testType",request.getType());
        tempCi.getOptions().putAll(option);
        tempCi.setNiceName(request.getPerfTool());
        return collectorService.createCollectorItem(tempCi);
    }


    private Collector createGenericCollector(TestCreateRequest request, String name) {
        CollectorRequest collectorReq = new CollectorRequest();
        collectorReq.setName(name);
        collectorReq.setCollectorType(CollectorType.Test);
        Collector col = collectorReq.toCollector();
        col.setEnabled(true);
        col.setOnline(true);
        col.setLastExecuted(System.currentTimeMillis());
        Map<String, Object> allOptions = new HashMap<>();
        allOptions.put("jobUrl", "");
        allOptions.put("jobName","");
        allOptions.put("instanceUrl", "");
        allOptions.put("testType","");
        col.setAllFields(allOptions);
        col.setUniqueFields(allOptions);
        return collectorService.createCollector(col);
    }

    private Map<String, String> getJobNameAndInstanceUrl(String jobUrl){
        Map<String ,String> map = new HashMap<>();
        if(StringUtils.isNotEmpty(jobUrl)){
            Pattern pattern = Pattern.compile("(.*?://)([^:^/]*)?(.*)?");
            Matcher matcher = pattern.matcher(jobUrl);
            matcher.find();
            String protocol = matcher.group(1);
            String domain = matcher.group(2);
            String uri = matcher.group(3);
                map.put("instanceUrl", protocol+domain);
                map.put("jobName", uri);
        }
        return map;
    }



    private CollectorItem createGenericCollectorItem(Collector collector,  TestCreateRequest request, String desc) {
        CollectorItem tempCi = new CollectorItem();
        tempCi.setCollectorId(collector.getId());
        tempCi.setDescription(desc);
        tempCi.setPushed(true);
        tempCi.setLastUpdated(System.currentTimeMillis());
        Map<String, Object> option = new HashMap<>();
        option.put("jobUrl", request.getJobUrl());
        Map<String,String> map = getJobNameAndInstanceUrl(request.getJobUrl());
        option.put("jobName",map.get("jobName"));
        option.put("instanceUrl", map.get("instanceUrl"));
        option.put("testType",request.getTestType());
        tempCi.getOptions().putAll(option);
        tempCi.setNiceName(request.getSourceFormat());
        return collectorService.createCollectorItem(tempCi);
    }



    private TestResult createTest(CollectorItem collectorItem, TestDataCreateRequest request) {
        TestResult testResult = testResultRepository.findByCollectorItemIdAndExecutionId(collectorItem.getId(),
                request.getExecutionId());
        if (testResult == null) {
            testResult = new TestResult();
        }
        testResult.setTargetAppName(request.getTargetAppName());
        testResult.setTargetEnvName(request.getTargetEnvName());
        testResult.setCollectorItemId(collectorItem.getId());
        testResult.setType(request.getType());
        testResult.setDescription(request.getDescription());
        testResult.setDuration(request.getDuration());
        testResult.setEndTime(request.getEndTime());
        testResult.setExecutionId(request.getExecutionId());
        testResult.setFailureCount(request.getFailureCount());
        testResult.setSkippedCount(request.getSkippedCount());
        testResult.setStartTime(request.getStartTime());
        testResult.setSuccessCount(request.getSuccessCount());
        if(request.getTimestamp() == 0) request.setTimestamp(System.currentTimeMillis());
        testResult.setTimestamp(request.getTimestamp());
        testResult.setTotalCount(request.getTotalCount());
        testResult.setUnknownStatusCount(request.getUnknownStatusCount());
        testResult.setUrl(request.getTestJobUrl());
        testResult.getTestCapabilities().addAll(request.getTestCapabilities());
        testResult.setBuildId(new ObjectId(request.getTestJobId()));
        testResult.setBuildArtifact(request.getBuildArtifact());
        return testResultRepository.save(testResult);
    }

    private TestResult createPerfTest(CollectorItem collectorItem, PerfTestDataCreateRequest request) {
        TestResult testResult = testResultRepository.findByCollectorItemIdAndExecutionId(collectorItem.getId(),
                request.getRunId());
        if (testResult == null) {
            testResult = new TestResult();
        }
        testResult.setTargetAppName(request.getTargetAppName());
        testResult.setTargetEnvName(request.getTargetEnvName());
        testResult.setCollectorItemId(collectorItem.getId());
        testResult.setType(request.getType());
        testResult.setDescription(request.getDescription());
        testResult.setDuration(request.getDuration());
        testResult.setEndTime(request.getEndTime());
        testResult.setExecutionId(request.getRunId());
        testResult.setFailureCount(request.getFailureCount());
        testResult.setSkippedCount(request.getSkippedCount());
        testResult.setStartTime(request.getStartTime());
        testResult.setSuccessCount(request.getSuccessCount());
        if(request.getTimestamp() == 0) request.setTimestamp(System.currentTimeMillis());
        testResult.setTimestamp(request.getTimestamp());
        testResult.setTotalCount(request.getTotalCount());
        testResult.setUnknownStatusCount(request.getUnknownStatusCount());
        testResult.setUrl(request.getReportUrl());
        testResult.getTestCapabilities().addAll(request.getTestCapabilities());
        testResult.setDescription(request.getDescription());
        testResult.setResultStatus(request.getResultStatus());
        testResult.setBuildArtifact(request.getBuildArtifact());
        testResult.setPerfRisk(request.getPerfRisk());
        return testResultRepository.save(testResult);
    }




    private TestResult createTestCucumber(CollectorItem collectorItem, CucumberJsonReport.Feature cucumberReport, TestSuiteType type, TestCreateRequest request, TestCapability cucumberTestCapabilityTransformer) {

        TestResult  testResult = new TestResult();
        Collection<TestCapability> testCapabilities = new ArrayList();
        testCapabilities.add(cucumberTestCapabilityTransformer);
        testResult.setTestCapabilities(testCapabilities);
        testResult.setType(type);
        testResult.setCollectorItemId(collectorItem.getId());
        testResult.setDescription(cucumberReport.getName());
        testResult.setTestCapabilities(testCapabilities);
        testResult.setType(type);
        testResult.setCollectorItemId(collectorItem.getId());
        testResult.setTargetEnvName(getConfigurationItem(request.getConfigurationItem(),request.getTargetAppName()));
        testResult.setTargetAppName(request.getTargetAppName());
        testResult.setDuration(cucumberTestCapabilityTransformer.getDuration());
        testResult.setFailureCount(cucumberTestCapabilityTransformer.getFailedTestSuiteCount());
        testResult.setSuccessCount(cucumberTestCapabilityTransformer.getSuccessTestSuiteCount());
        testResult.setSkippedCount(cucumberTestCapabilityTransformer.getSkippedTestSuiteCount());
        testResult.setTotalCount(cucumberTestCapabilityTransformer.getTotalTestSuiteCount());
        testResult.setUnknownStatusCount(cucumberTestCapabilityTransformer.getUnknownStatusTestSuiteCount());
        testResult.setTimestamp(convertTimestamp(request.getTimeStamp()));
        testResult.getTestCapabilities().addAll(testCapabilities);
        // associate build data
        associateBuildToTestResult(request.getJobUrl(), request.getClientReference(), testResult);
        testResult.setClientReference(request.getClientReference());
        TestResult result = testResultRepository.save(testResult);
        return result;
    }

    private void associateBuildToTestResult(String buildUrl, String clientReference, TestResult testResult) {
        if (Objects.isNull(buildUrl)) return;
        Build build = buildRepository.findByBuildUrl(buildUrl);
        if (Objects.nonNull(build)) {
            build.setClientReference(clientReference);
            testResult.setBuildId(build.getId());
            buildRepository.save(build);
        } else {
            Build baseBuild = new Build();
            baseBuild.setBuildUrl(buildUrl);
            String trailedBuildUrl = HygieiaUtils.normalizeJobUrl(buildUrl);
            Collector collector = collectorRepository.findByName(apiSettings.getBuildCollectorName());
            CollectorItem buildCollectorItem = collectorItemRepository.findByJobUrl(collector.getId(), trailedBuildUrl);
            if(Objects.nonNull(buildCollectorItem)){
                baseBuild.setCollectorItemId(buildCollectorItem.getId());
            }
            baseBuild.setBuildStatus(BuildStatus.InProgress);
            baseBuild.setClientReference(clientReference);
            baseBuild = buildRepository.save(baseBuild);
            testResult.setBuildId(baseBuild.getId());
        }
    }


    private TestResult createTestJunit(CollectorItem collectorItem, JunitXmlReport junitXmlReport, TestSuiteType type, TestCreateRequest request, TestCapability testCapability) {

        TestResult  testResult = new TestResult();
        Collection<TestCapability> testCapabilities = new ArrayList();
        testCapabilities.add(testCapability);
        testResult.setTestCapabilities(testCapabilities);
        testResult.setType(type);
        testResult.setCollectorItemId(collectorItem.getId());
        testResult.setDescription(junitXmlReport.getName());
        testResult.setTargetEnvName(getConfigurationItem(request.getConfigurationItem(),request.getTargetAppName()));
        testResult.setTargetAppName((request.getTargetAppName()));
        testResult.setDuration(junitXmlReport.getTime().longValue());
        testResult.setFailureCount(junitXmlReport.getFailures());
        testResult.setSuccessCount(testCapability.getSuccessTestSuiteCount());
        testResult.setSkippedCount(StringUtils.isNotEmpty(junitXmlReport.getSkipped())? Integer.parseInt(junitXmlReport.getSkipped()):StringUtils.isNotEmpty(junitXmlReport.getSkips())? Integer.parseInt(junitXmlReport.getSkips()): 0);
        testResult.setTotalCount(junitXmlReport.getTests());
        testResult.setUnknownStatusCount(testCapability.getUnknownStatusTestSuiteCount());
        testResult.setTimestamp(convertTimestamp(request.getTimeStamp()));
        // associate build data
        associateBuildToTestResult(request.getJobUrl(), request.getClientReference(), testResult);
        testResult.setClientReference(request.getClientReference());
        TestResult result = testResultRepository.save(testResult);
        return result;
    }

    private TestResult createTestJunitV2(CollectorItem collectorItem, JunitXmlReportV2.TestSuite junitXmlReportTestsuite, TestSuiteType type, TestCreateRequest request, TestCapability testCapability) {
        TestResult  testResult = new TestResult();
        Collection<TestCapability> testCapabilities = new ArrayList();
        testCapabilities.add(testCapability);
        testResult.setTestCapabilities(testCapabilities);
        testResult.setType(type);
        testResult.setCollectorItemId(collectorItem.getId());
        testResult.setDescription(junitXmlReportTestsuite.getName());
        testResult.setTargetEnvName(getConfigurationItem(request.getConfigurationItem(),request.getTargetAppName()));
        testResult.setTargetAppName((request.getTargetAppName()));
        testResult.setDuration(junitXmlReportTestsuite.getTime().longValue());
        testResult.setFailureCount(junitXmlReportTestsuite.getFailures());
        testResult.setSuccessCount(testCapability.getSuccessTestSuiteCount());
        testResult.setSkippedCount(StringUtils.isNotEmpty(junitXmlReportTestsuite.getSkipped())? Integer.parseInt(junitXmlReportTestsuite.getSkipped()):StringUtils.isNotEmpty(junitXmlReportTestsuite.getSkips())? Integer.parseInt(junitXmlReportTestsuite.getSkips()): 0);
        testResult.setTotalCount(junitXmlReportTestsuite.getTests());
        testResult.setUnknownStatusCount(testCapability.getUnknownStatusTestSuiteCount());
        testResult.setTimestamp(convertTimestamp(request.getTimeStamp()));
        TestResult result = testResultRepository.save(testResult);
        return result;
    }

    private String getConfigurationItem(String configurationItem, String targetAppName){

        if (StringUtils.isNotBlank(configurationItem))
        {
            return configurationItem;
        }
        else if (StringUtils.isNotBlank(targetAppName) ){
            List<Cmdb> cmdb = cmdbService.configurationItemsByTypeWithFilter("", targetAppName,
                   new PageRequest(0, 1)).getContent();
           if(cmdb.size() > 0){
               return cmdb.get(0).getConfigurationItem();
           }
        }

        return null;
    }

    private void validConfigurationItem(String configurationItem, String targetAppName) throws HygieiaException{

        if (StringUtils.isBlank(configurationItem))
        {
            if (StringUtils.isBlank(targetAppName)){
                throw new HygieiaException("targetAppName should not be null.",HygieiaException.BAD_DATA);

            }
        }
    }

    private long convertTimestamp (String timestamp){

        long time = 0;

        if(StringUtils.isNotEmpty(timestamp)){
          time = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS Z").parseMillis(timestamp);

        }else{
            time = System.currentTimeMillis();
        }


        return time;
    }


    private void  getLocation(){
        this.fileStorageLocation = Paths.get(apiSettings.getHygieiafile())
                .toAbsolutePath().normalize();
        if(!Files.isDirectory(this.fileStorageLocation)){
            try {
                Files.createDirectories(this.fileStorageLocation);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }


    public String storeFile(MultipartFile file) throws HygieiaException {
        getLocation();
        String fileName = org.springframework.util.StringUtils.cleanPath(file.getOriginalFilename());
        try {
            Path targetLocation = this.fileStorageLocation.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            String unzipFloderName = fileName.substring(0,fileName.lastIndexOf("."));
            Path unzipLocation = this.fileStorageLocation.resolve(unzipFloderName);
            this.unzipFolder(targetLocation,unzipLocation);
            Files.deleteIfExists(Paths.get(targetLocation.toString()));
            return unzipLocation.toString();
        } catch (IOException ex) {
            throw new HygieiaException("Error on file unzipping : "+ ex.getMessage(), HygieiaException.BAD_DATA);
        }
    }


    private   void unzipFolder(Path source, Path target) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(source.toFile()))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                boolean isDirectory = false;
                if (zipEntry.getName().endsWith(File.separator)) {
                    isDirectory = true;
                }
                Path newPath = zipSlipProtect(zipEntry, target);
                if (isDirectory) {
                    Files.createDirectories(newPath);
                } else {

                    if (newPath.getParent() != null) {
                        if (Files.notExists(newPath.getParent())) {
                            Files.createDirectories(newPath.getParent());
                        }
                    }
                    Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
                }

                zipEntry = zis.getNextEntry();

            }
            zis.closeEntry();
        }
    }

    private   Path zipSlipProtect(ZipEntry zipEntry, Path targetDir)
            throws IOException {
        Path targetDirResolved = targetDir.resolve(zipEntry.getName());
        Path normalizePath = targetDirResolved.normalize();
        if (!normalizePath.startsWith(targetDir)) {
            throw new IOException("Bad zip entry: " + zipEntry.getName());
        }
        return normalizePath;
    }

    public <T> T fileRedear(File file, Class<T> type ) throws HygieiaException{
        String data = "";
        try {
            data = new String(Files.readAllBytes(Paths.get(file.getPath())));
        } catch (IOException e) {
            throw new HygieiaException("Error in file reading : " + e.getMessage(), HygieiaException.BAD_DATA);
        }
        Gson gson = new Gson();
        return gson.fromJson(data , type);
    }


    public void deleteDirectory(String dir) throws HygieiaException {

        Path path = Paths.get(dir);
        try (Stream<Path> walk = Files.walk(path)) {
            walk
                    .sorted(Comparator.reverseOrder())
                    .forEach(TestResultServiceImpl::deleteDirectoryExtract);
        }
        catch (IOException ex){
            throw new HygieiaException("Error while deleting folder : "+ ex.getMessage(),HygieiaException.BAD_DATA);
        }

    }

    private static void deleteDirectoryExtract(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            System.err.printf("Unable to delete this path : %s%n%s", path, e);
        }
    }


}
