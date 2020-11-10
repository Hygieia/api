package com.capitalone.dashboard.webhook.github;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.CollectorItemRepository;
import com.capitalone.dashboard.settings.ApiSettings;
import com.capitalone.dashboard.client.RestClient;
import com.capitalone.dashboard.model.webhook.github.GitHubParsed;
import com.capitalone.dashboard.misc.HygieiaException;
import com.capitalone.dashboard.repository.CommitRepository;
import com.capitalone.dashboard.repository.GitRequestRepository;
import com.capitalone.dashboard.service.CollectorService;
import com.capitalone.dashboard.webhook.settings.GitHubWebHookSettings;
import com.capitalone.dashboard.webhook.settings.WebHookSettings;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.springframework.http.ResponseEntity;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitHubCommitV3 extends GitHubV3 {
    private static final Log LOG = LogFactory.getLog(GitHubCommitV3.class);

    private final CommitRepository commitRepository;
    private final GitRequestRepository gitRequestRepository;
    private final CollectorItemRepository collectorItemRepository;

    public GitHubCommitV3(CollectorService collectorService,
                          RestClient restClient,
                          CommitRepository commitRepository,
                          GitRequestRepository gitRequestRepository,
                          CollectorItemRepository collectorItemRepository,
                          ApiSettings apiSettings) {
        super(collectorService, restClient, apiSettings);

        this.commitRepository = commitRepository;
        this.gitRequestRepository = gitRequestRepository;
        this.collectorItemRepository = collectorItemRepository;
    }

    @Override
    public CollectorItemRepository getCollectorItemRepository() { return this.collectorItemRepository; }

    @Override
    public String process(JSONObject jsonObject) throws MalformedURLException, HygieiaException, ParseException {
        String result = "Commits Processed Successfully";

        String branch = null;
        String repoUrl = null;

        Object commitsObj = jsonObject.get("commits");
        if (!(commitsObj instanceof List)) {
            result = "No Commits Data Found";
            return result;
        }

        List<Map> commitsObjectList = (ArrayList<Map>) jsonObject.get("commits");

        if (CollectionUtils.isEmpty(commitsObjectList)) {
            return "Commits JSONArray Empty.";
        }

        Object repoObject = jsonObject.get("repository");
        if (!(repoObject instanceof Map)) {
            return "No Repository Found";
        }

        repoUrl = restClient.getString(repoObject, "url");
        String ref = restClient.getString(jsonObject, "ref");
        if (!StringUtils.isEmpty(ref)) {
            branch = ref.replace("refs/heads/", "");
        }

        if(!isRegistered(repoUrl, branch)) {
            return "Repo: <" + repoUrl + "> Branch: <" + branch + "> is not registered in Hygieia";
        }

        Object senderObj = jsonObject.get("sender");
        List<Commit> commitList = getCommits(commitsObjectList, repoUrl, branch, senderObj);

        commitRepository.save(commitList);

        return result;
    }

    protected List<Commit> getCommits(List<Map> commitsObjectList, String repoUrl,
                                      String branch, Object senderObj) throws MalformedURLException, HygieiaException, ParseException {
        List<Commit> commitsList = new ArrayList<>();

        GitHubParsed gitHubParsed = new GitHubParsed(repoUrl);

        WebHookSettings webHookSettings = apiSettings.getWebHook();

        if (webHookSettings == null) {
            LOG.info("Github Webhook properties not set on the properties file. Returning ...");
            return commitsList;
        }

        GitHubWebHookSettings gitHubWebHookSettings = webHookSettings.getGitHub();

        if (gitHubWebHookSettings == null) {
            LOG.info("Github Webhook properties not set on the properties file. Returning ...");
            return commitsList;
        }

        List<Pattern> commitExclusionPatterns = new ArrayList<>();
        Optional.ofNullable(gitHubWebHookSettings.getNotBuiltCommits())
            .orElseGet(Collections::emptyList).stream()
            .map(regExStr -> Pattern.compile(regExStr, Pattern.CASE_INSENSITIVE))
            .forEach(commitExclusionPatterns::add);

        for (Map cObj : commitsObjectList) {
            Object repoMap = restClient.getAsObject(cObj, "repository");
            boolean isPrivate = restClient.getBoolean(repoMap, "private");

            long start = System.currentTimeMillis();

            String repoToken = getRepositoryToken(gitHubParsed.getUrl());

            long end = System.currentTimeMillis();

            LOG.debug("Time to make collectorItemRepository call to fetch repository token = "+(end-start));

            String gitHubWebHookToken =  isPrivate ? RestClient.decryptString(repoToken, apiSettings.getKey()) : gitHubWebHookSettings.getToken();

            if (StringUtils.isEmpty(gitHubWebHookToken)) {
                throw new HygieiaException("Failed processing payload. Missing Github API token in Hygieia.", HygieiaException.INVALID_CONFIGURATION);
            }

            Commit commit = new Commit();

            String commitId = restClient.getString(cObj, "id");
            commit.setScmRevisionNumber(commitId);

            Object authorObjectFromCommit = restClient.getAsObject(cObj,"author");
            commit.setScmAuthor(restClient.getString(authorObjectFromCommit, "name"));

            String message = restClient.getString(cObj, "message");
            commit.setScmCommitLog(message);

            commit.setTimestamp(System.currentTimeMillis());
            commit.setScmUrl(repoUrl);
            commit.setScmBranch(branch);

            DateTime commitTimestamp = new DateTime(restClient.getString(cObj, "timestamp"));
            commit.setScmCommitTimestamp(commitTimestamp.getMillis());

            Object node = getCommitNode(gitHubParsed, commitId, gitHubWebHookToken);
            if (node == null) {
                LOG.debug("Commit node not found for " + commitId);
            } else  {
                List<String> parentShas = getParentShas(node);
                commit.setScmParentRevisionNumbers(parentShas);
                commit.setFirstEverCommit(CollectionUtils.isEmpty(parentShas));
                commit.setType(getCommitType(parentShas.size(), message, gitHubWebHookSettings, commitExclusionPatterns));

                Object authorObject = restClient.getAsObject(node, "author");
                Object userObject = restClient.getAsObject(authorObject, "user");
                String authorLogin = (userObject == null) ? "unknown" : restClient.getString(userObject, "login");
                commit.setScmAuthorLogin(authorLogin);

                if (senderObj != null && authorLogin.equalsIgnoreCase(restClient.getString(senderObj, "login"))) {
                    String authorType = restClient.getString(senderObj, "type");
                    if (!StringUtils.isEmpty(authorType)) {
                        commit.setScmAuthorType(authorType);
                    }
                    String authorLDAPDN = restClient.getString(senderObj, "ldap_dn");
                    if (!StringUtils.isEmpty(authorLDAPDN)) {
                        commit.setScmAuthorLDAPDN(authorLDAPDN);
                    }
                } else {
                    start = System.currentTimeMillis();

                    String authorType = getAuthorType(repoUrl, authorLogin, gitHubWebHookToken);
                    if (!StringUtils.isEmpty(authorType)) {
                        commit.setScmAuthorType(authorType);
                    }
                    String authorLDAPDN = getLDAPDN(repoUrl, authorLogin, gitHubWebHookToken);
                    if (!StringUtils.isEmpty(authorLDAPDN)) {
                        commit.setScmAuthorLDAPDN(authorLDAPDN);
                    }

                    end = System.currentTimeMillis();
                    LOG.debug("Time to fetch LDAPDN = "+(end-start));
                }

                // Set the Committer details. This in the case of a merge commit is the user who merges the PR.
                // In the case of a regular commit, it is usually set to a default "name": "GitHub Enterprise", and login is null
                Object committerObject = restClient.getAsObject(node, "committer");
                Object committerUserObject = restClient.getAsObject(committerObject, "user");
                String committerLogin = (committerUserObject == null) ? "unknown" : restClient.getString(committerUserObject, "login");
                commit.setScmCommitterLogin(committerLogin);
            }
            // added fields to capture files
            int numberChanges = 0;
            if (cObj.get("added") instanceof List) {
                numberChanges += ((List) cObj.get("added")).size();
                commit.setFilesAdded((List) cObj.get("added"));
            }
            if (cObj.get("removed") instanceof List) {
                numberChanges += ((List) cObj.get("removed")).size();
                commit.setFilesRemoved((List) cObj.get("removed"));
            }
            if (cObj.get("modified") instanceof List) {
                numberChanges += ((List) cObj.get("modified")).size();
                commit.setFilesModified((List) cObj.get("modified"));
            }

            List<RepoFile> files = (List<RepoFile>) cObj.get("files");
            for (RepoFile file: files) {
                file.setFilename(file.getFilename());
                file.setPatch(file.getPatch());
                // TODO: add patch check
            }
            commit.setFiles(files);

            commit.setNumberOfChanges(numberChanges);
            setCommitPullNumber(commit);
            setCollectorItemId(commit);
            commitsList.add(commit);
        }

        // For a merge commit for "Rebase and Merge"
        setCommitPullNumbersForRebaseAndMergeCommit(commitsList);

        return commitsList;
    }

    protected void setCommitPullNumbersForRebaseAndMergeCommit(List<Commit> commitsList) {
        List<Commit> commitsWithPullNumber = Optional.ofNullable(commitsList)
                                            .orElseGet(Collections::emptyList).stream()
                                            .filter(commit -> !StringUtils.isEmpty(commit.getPullNumber()))
                                            .collect(Collectors.toList());

        // In case of Rebase and Merge, only the last commit in the list of commits on the "merge commit" json should have the PR number.
        // This is because, there should be a corresponding PR in the DB whose "merge_commit_sha" matches the last commit in the list of commits
        if (checkCommitsWithPullNumber(commitsWithPullNumber)
                && checkCommitsListForSettingPullNumber(commitsList)) {
            Commit commitWithPR = commitsWithPullNumber.get(0);
            commitsList.forEach(commit -> {commit.setPullNumber(commitWithPR.getPullNumber());});
        }
    }

    private boolean checkCommitsWithPullNumber(List<Commit> commitsWithPullNumber) {
        return !CollectionUtils.isEmpty(commitsWithPullNumber)
                && (commitsWithPullNumber.size() == 1);
    }

    private boolean checkCommitsListForSettingPullNumber(List<Commit> commitsList) {
        return !CollectionUtils.isEmpty(commitsList) && (commitsList.size() > 1);
    }

    protected void setCommitPullNumber (Commit commit) {
        GitRequest pr = gitRequestRepository.findByScmRevisionNumberOrScmMergeEventRevisionNumber(commit.getScmRevisionNumber());
        if (pr == null) {
            pr = gitRequestRepository.findByCommitScmRevisionNumber(commit.getScmRevisionNumber());
        }
        if (pr != null) {
            commit.setPullNumber(pr.getNumber());
        }
    }

    protected void setCollectorItemId (Commit commit) throws MalformedURLException, HygieiaException {
        List<Commit> existingCommits
                = commitRepository.findAllByScmRevisionNumberAndScmUrlIgnoreCaseAndScmBranchIgnoreCaseOrderByTimestampAsc(commit.getScmRevisionNumber(), commit.getScmUrl(), commit.getScmBranch());

        if (!CollectionUtils.isEmpty(existingCommits)) {
            commit.setId(existingCommits.get(0).getId());
            commit.setCollectorItemId(existingCommits.get(0).getCollectorItemId());
            CollectorItem collectorItem = collectorService.getCollectorItem(existingCommits.get(0).getCollectorItemId());
            collectorItem.setEnabled(true);
            collectorItem.setPushed(true);
            collectorItemRepository.save(collectorItem);
        } else {
            GitHubParsed gitHubParsed = new GitHubParsed(commit.getScmUrl());
            CollectorItem collectorItem = getCollectorItem(gitHubParsed.getUrl(), commit.getScmBranch());
            commit.setCollectorItemId(collectorItem.getId());
        }
    }

    protected Object getCommitNode(GitHubParsed gitHubParsed, String commitId, String token) throws HygieiaException, ParseException {

        JSONObject postBody = getQuery(gitHubParsed, commitId, GraphQLQuery.COMMITS_GRAPHQL);

        ResponseEntity<String> response = null;
        int retryCount = 0;
        while(true) {
            try {
                response = restClient.makeRestCallPost(gitHubParsed.getGraphQLUrl(), "token", token, postBody);
                break;
            } catch (Exception e) {
                retryCount++;
                if(retryCount > apiSettings.getWebHook().getGitHub().getMaxRetries()) {
                    LOG.error("Unable to get COMMIT from " + gitHubParsed.getUrl() + " after " + apiSettings.getWebHook().getGitHub().getMaxRetries() + " tries!");
                    throw new HygieiaException(e);
                }
            }
        }

        JSONObject responseJsonObject = restClient.parseAsObject(response);
        if (responseJsonObject == null) { return null; }

        checkForErrors(responseJsonObject);

        JSONObject commitData = (JSONObject) responseJsonObject.get("data");
        if (commitData == null) { return null; }

        JSONObject repoData = (JSONObject) commitData.get("repository");
        if (repoData == null) { return null; }

        return repoData.get("object");
    }

    protected List<String> getParentShas(Object commit) {
        Object parents = restClient.getAsObject(commit, "parents");
        Object nodes = restClient.getAsObject(parents, "nodes");
        List<String> parentShas = new ArrayList<>();

        if (nodes instanceof JSONArray) {
            JSONArray parentNodes = (JSONArray) nodes;
            for (Object parentObj : parentNodes) {
                parentShas.add(restClient.getString(parentObj, "oid"));
            }
        }

        return parentShas;
    }

    protected CommitType getCommitType(int parentSize, String commitMessage,
                                       GitHubWebHookSettings gitHubWebHookSettings,
                                       List<Pattern> commitExclusionPatterns) {
        if (parentSize > 1) return CommitType.Merge;
        if (CollectionUtils.isEmpty(gitHubWebHookSettings.getNotBuiltCommits())) return CommitType.New;

        if (!CollectionUtils.isEmpty(commitExclusionPatterns)) {
            for (Pattern pattern : commitExclusionPatterns) {
                if (pattern.matcher(commitMessage).matches()) {
                    return CommitType.NotBuilt;
                }
            }
        }
        return CommitType.New;
    }

    protected JSONObject getQuery (GitHubParsed gitHubParsed, String commitId, String queryString) {
        JSONObject query = new JSONObject();
        JSONObject variableJSON = new JSONObject();
        variableJSON.put("owner", gitHubParsed.getOrgName());
        variableJSON.put("name", gitHubParsed.getRepoName());
        variableJSON.put("oid", commitId);
        query.put("query", queryString);
        query.put("variables", variableJSON.toString());
        return query;
    }

}
