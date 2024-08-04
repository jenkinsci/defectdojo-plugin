package io.jenkins.plugins.DefectDojo;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import jenkins.security.Roles;
import net.sf.json.JSONObject;
import org.jenkinsci.remoting.RoleChecker;

public class ReportUploadCallable implements FilePath.FileCallable<Boolean> {

    private final JSONObject jsonBody;
    private final String url;
    private final ApiClient apiClient;

    public ReportUploadCallable(ApiClient apiClient, JSONObject jsonBody, String url) {
        this.apiClient = apiClient;
        this.jsonBody = jsonBody;
        this.url = url;
    }

    @Override
    public Boolean invoke(File file, VirtualChannel channel) throws IOException, InterruptedException {
        return apiClient.performUpload(file, jsonBody, url);
    }

    @Override
    public void checkRoles(RoleChecker checker) throws SecurityException {
        checker.check(this, Roles.SLAVE);
    }
}
