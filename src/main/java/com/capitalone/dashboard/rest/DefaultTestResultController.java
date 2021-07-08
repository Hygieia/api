package com.capitalone.dashboard.rest;

import com.capitalone.dashboard.editors.CaseInsensitiveTestSuiteTypeEditor;
import com.capitalone.dashboard.misc.HygieiaException;
import com.capitalone.dashboard.model.CodeQualityType;
import com.capitalone.dashboard.model.DataResponse;
import com.capitalone.dashboard.model.TestCreateRequest;
import com.capitalone.dashboard.model.TestResult;
import com.capitalone.dashboard.request.PerfTestDataCreateRequest;
import com.capitalone.dashboard.request.TestDataCreateRequest;
import com.capitalone.dashboard.request.TestResultRequest;
import com.capitalone.dashboard.service.TestResultService;
import com.capitalone.dashboard.util.CommonConstants;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import java.io.File;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@RestController
public class DefaultTestResultController {
    private final HttpServletRequest httpServletRequest;
    private final TestResultService testResultService;

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTestResultController.class);

    @Autowired
    public DefaultTestResultController(HttpServletRequest httpServletRequest, TestResultService testResultService) {
        this.httpServletRequest = httpServletRequest;
        this.testResultService = testResultService;
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(CodeQualityType.class, new CaseInsensitiveTestSuiteTypeEditor());
    }

    @RequestMapping(value = "/quality/test", method = GET, produces = APPLICATION_JSON_VALUE)
    public DataResponse<Iterable<TestResult>> qualityData(@Valid TestResultRequest request) {
        return testResultService.search(request);
    }


    @RequestMapping(value = "/quality/test", method = POST,
                consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
        public ResponseEntity<String> createTest(@Valid @RequestBody TestDataCreateRequest request) throws HygieiaException {
            String response = testResultService.create(request);
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);
    }

    @RequestMapping(value = "/v2/quality/test", method = POST,
            consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createTestV2(@Valid @RequestBody TestDataCreateRequest request) throws HygieiaException {
        String response = testResultService.createV2(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @RequestMapping(value = "/quality/testresult", method = POST,
            consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createPerfTest(@Valid @RequestBody PerfTestDataCreateRequest request) throws HygieiaException {
        String response = testResultService.createPerf(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @RequestMapping(value = "/v2/quality/testresult", method = POST,
            consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createPerfTestV2(@Valid @RequestBody PerfTestDataCreateRequest request) throws HygieiaException {
        String response = testResultService.createPerfV2(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @RequestMapping(value = "/quality/test-result", method = POST,
            consumes = "application/json;v=3", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createTest(@Valid @RequestBody TestCreateRequest request) throws HygieiaException {
        String correlation_id = httpServletRequest.getHeader(CommonConstants.HEADER_CLIENT_CORRELATION_ID);
        String requester = httpServletRequest.getHeader(CommonConstants.HEADER_API_USER);
        request.setClientReference(correlation_id);
        String response = testResultService.createTest(request);

        //temporary fix to ensure backward compatibility
        boolean success = !StringUtils.containsIgnoreCase(response, "Hygieia does not support");
        HttpStatus httpStatus = success ? HttpStatus.CREATED : HttpStatus.BAD_REQUEST;
        String response_status = success ? "success" : "failed";
        LOGGER.info("correlation_id=" + correlation_id + ", application=hygieia, service=api, uri=" + httpServletRequest.getRequestURI() +
                        ", requester=" + requester + ", response_status=" + response_status + ", response_code=" + httpStatus.value() +
                        ", response_status_message=" + response + ", test_type=" + request.getTestType() +
                        ", test_source_format="+request.getSourceFormat() + ", test_source=" + request.getSource() +
                        ", target_app_name=" + request.getTargetAppName() + ", target_service_name=" + request.getTargetServiceName() +
                        ", test_job_url=" + request.getJobUrl());

        return ResponseEntity
                .status(httpStatus)
                .body(response);
    }


    @RequestMapping(value = "/quality/test-result/import", method = POST)
    public ResponseEntity<String> uploadZIPFile(@RequestParam("file") MultipartFile file) throws HygieiaException {

        String filePath = testResultService.storeFile(file);
        StringBuilder res = new StringBuilder();
        if(filePath != null) {
            File directoryPath = new File(filePath);
            File filesList[] = directoryPath.listFiles();
            for (File fileRedr : filesList) {
                TestCreateRequest request = testResultService.fileRedear(fileRedr, TestCreateRequest.class );
                String response = testResultService.createTest(request);
                res.append(response);
            }
            testResultService.deleteDirectory(filePath);
        }
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(res.toString());
    }


}
