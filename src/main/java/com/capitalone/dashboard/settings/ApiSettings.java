package com.capitalone.dashboard.settings;

import com.capitalone.dashboard.webhook.settings.DataSyncSettings;
import com.capitalone.dashboard.webhook.settings.GithubSyncSettings;
import com.capitalone.dashboard.webhook.settings.SonarDataSyncSettings;
import com.capitalone.dashboard.webhook.settings.WebHookSettings;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties
public class ApiSettings {
    /**
     * TODO The property name 'key' is too vague. This key is used only for encryption. Would suggest to rename it to
     * encryptionKey to be specific. For now (for backwards compatibility) keeping it as it was.
     */
    private String key;
    @Value("${corsEnabled:false}")
    private boolean corsEnabled;
    private String corsWhitelist;
    private boolean logRequest;
    @Value("${pageSize:10}")
    private int pageSize;
    @Value("${lookupDashboardForBuildDataCreate:false}")
    private boolean lookupDashboardForBuildDataCreate;

    @Value("${gitToolName:Github}")
    private String gitToolName;

    @Value("${buildCollectorName:Hudson}")
    private String buildCollectorName;

    private WebHookSettings webHook;

    private String capturePattern;

    private List<String> ignoreEndPoints = new ArrayList();
    private List<String> ignoreApiUsers = new ArrayList();
    private List<String> ignoreBodyEndPoints = new ArrayList();

    private GithubSyncSettings githubSyncSettings = new GithubSyncSettings();
    private SonarDataSyncSettings sonarDataSyncSettings = new SonarDataSyncSettings();
    private DataSyncSettings dataSyncSettings = new DataSyncSettings();

    private Map<String,String> functional;
    private Map<String,String> performance;
    private String unit;

    @Value("${encryptRemoteCreatePayload:true}")
    private boolean encryptRemoteCreatePayload;

    private String hygieia_ui_url="";

    private String hygieiafile;

    public String getHygieiafile() {
        return hygieiafile;
    }

    public void setHygieiafile(String hygieiafile) {
        this.hygieiafile = hygieiafile;
    }

    public Map<String, String> getFunctional() {
        return functional;
    }

    public void setFunctional(Map<String, String> functional) {
        this.functional = functional;
    }

    public Map<String, String> getPerformance() {
        return performance;
    }

    public void setPerformance(Map<String, String> performance) {
        this.performance = performance;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public WebHookSettings getWebHook() {
        return webHook;
    }

    public void setWebHook(WebHookSettings webHook) {
        this.webHook = webHook;
    }

    public String getKey() {
        return key;
    }

    public void setKey(final String key) {
        this.key = key;
    }

    public boolean isCorsEnabled() {
        return corsEnabled;
    }

    public void setCorsEnabled(boolean corsEnabled) {
        this.corsEnabled = corsEnabled;
    }

    public String getCorsWhitelist() {
        return corsWhitelist;
    }

    public void setCorsWhitelist(String corsWhitelist) {
        this.corsWhitelist = corsWhitelist;
    }

    public boolean isLogRequest() {
        return logRequest;
    }

    public void setLogRequest(boolean logRequest) {
        this.logRequest = logRequest;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public boolean isLookupDashboardForBuildDataCreate() { return lookupDashboardForBuildDataCreate; }

    public void setLookupDashboardForBuildDataCreate(boolean lookupDashboardForBuildDataCreate) {
        this.lookupDashboardForBuildDataCreate = lookupDashboardForBuildDataCreate;
    }

    public String getGitToolName() {
        return gitToolName;
    }

    public void setGitToolName(String gitToolName) {
        this.gitToolName = gitToolName;
    }

    public String getCapturePattern() {
        return capturePattern;
    }

    public void setCapturePattern(String capturePattern) {
        this.capturePattern = capturePattern;
    }

    public List<String> getIgnoreEndPoints() {
        return ignoreEndPoints;
    }

    public void setIgnoreEndPoints(List<String> ignoreEndPoints) {
        this.ignoreEndPoints = ignoreEndPoints;
    }

    public List<String> getIgnoreApiUsers() { return ignoreApiUsers; }

    public void setIgnoreApiUsers(List<String> ignoreApiUsers) { this.ignoreApiUsers = ignoreApiUsers; }

    public List<String> getIgnoreBodyEndPoints() { return ignoreBodyEndPoints; }

    public void setIgnoreBodyEndPoints(List<String> ignoreBodyEndPoints) { this.ignoreBodyEndPoints = ignoreBodyEndPoints; }

    public boolean checkIgnoreEndPoint(String endPointURI) { return !getIgnoreEndPoints().isEmpty() && getIgnoreEndPoints().contains(endPointURI); }

    public boolean checkIgnoreApiUser(String apiUser) {
        if(CollectionUtils.isEmpty(this.ignoreApiUsers)) return false;
        List<String> matchingElements  = ignoreApiUsers.parallelStream().filter (str -> StringUtils.equalsIgnoreCase(apiUser, str)).collect(Collectors.toList());
        return CollectionUtils.isNotEmpty(matchingElements);
    }

    public boolean checkIgnoreBodyEndPoint(String endPointURI) {
        if(CollectionUtils.isEmpty(this.ignoreBodyEndPoints)) return false;
        List<String> matchingElements  = ignoreBodyEndPoints.parallelStream().filter (str -> StringUtils.equalsIgnoreCase(endPointURI, str)).collect(Collectors.toList());
        return CollectionUtils.isNotEmpty(matchingElements);
    }

    public GithubSyncSettings getGithubSyncSettings() {
        return githubSyncSettings;
    }

    public void setGithubSyncSettings(GithubSyncSettings githubSyncSettings) {
        this.githubSyncSettings = githubSyncSettings;
    }

    public SonarDataSyncSettings getSonarDataSyncSettings() {
        return sonarDataSyncSettings;
    }

    public void setSonarDataSyncSettings(SonarDataSyncSettings sonarDataSyncSettings) {
        this.sonarDataSyncSettings = sonarDataSyncSettings;
    }

    public DataSyncSettings getDataSyncSettings() {
        return dataSyncSettings;
    }

    public void setDataSyncSettings(DataSyncSettings dataSyncSettings) {
        this.dataSyncSettings = dataSyncSettings;
    }

    public boolean isEncryptRemoteCreatePayload() { return encryptRemoteCreatePayload; }

    public void setEncryptRemoteCreatePayload(boolean encryptRemoteCreatePayload) { this.encryptRemoteCreatePayload = encryptRemoteCreatePayload; }

    public String getHygieia_ui_url() { return hygieia_ui_url; }

    public void setHygieia_ui_url(String hygieia_ui_url) { this.hygieia_ui_url = hygieia_ui_url; }

    public String getBuildCollectorName() {
        return buildCollectorName;
    }

    public void setBuildCollectorName(String buildCollectorName) {
        this.buildCollectorName = buildCollectorName;
    }
}
