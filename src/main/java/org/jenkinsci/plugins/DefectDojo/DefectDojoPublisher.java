/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.DefectDojo;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import java.io.IOException;
import java.io.Serializable;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.tasks.Recorder;
import hudson.util.Secret;
import java.util.Optional;
import jenkins.tasks.SimpleBuildStep;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

@Getter
@Setter(onMethod_ = {@DataBoundSetter})
@EqualsAndHashCode(callSuper = true)
public final class DefectDojoPublisher extends Recorder implements SimpleBuildStep, Serializable {

    private static final long serialVersionUID = 480115440498217963L;

    /**
     * the product ID to upload to. This is a per-build config item.
     */
    private String productId;

    /**
     * the product name to upload to. This is a per-build config item.
     */
    private String productName;

     /**
     * the engagement ID to upload to. This is a per-build config item.
     */
    private String engagementId;

    /**
     * the engagement name to upload to. This is a per-build config item.
     */
    private String engagementName;

    /**
     * the source code uri. This is a per-build config item.
     */
    private String sourceCodeUrl;

    /**
     * the commit hash. This is a per-build config item.
     */
    private String commitHash;

    /**
     * the the branch name. This is a per-build config item.
     */
    private String branchTag;

    /**
     * Retrieves the path and filename of the artifact. This is a per-build
     * config item.
     */
    private final String artifact;

    /**
     * the scan type that will be uploaded. This is a per-build config item.
     */
    private final String scanType;

    /**
     * Specifies the base URL to DefectDojo.
     */
    private String defectDojoUrl;

    /**
     * Specifies the credential-id for an API Key used for authentication.
     */
    private String defectDojoApiKey;

    /**
     * Specifies if reupload of scan results is enabled.
     */
    private Boolean defectDojoReuploadScan;

    /**
     * the connection-timeout in seconds for every call to DT
     */
    private Integer defectDojoConnectionTimeout;

    /**
     * the read-timeout in seconds for every call to DT
     */
    private Integer defectDojoReadTimeout;

    /**
     * Specifies if product has to be created before upload.
     */
    private Boolean autoCreateProducts;

    /**
     * Specifies if engagement has to be created before upload.
     */
    private Boolean autoCreateEngagements;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient ApiClientFactory clientFactory;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient DescriptorImpl descriptor;

    private transient boolean overrideGlobals;
    
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient String projectIdCache;

    // Fields in config.jelly must match the parameter names
    @DataBoundConstructor
    public DefectDojoPublisher(final String artifact, final String scanType) {
        this(artifact, scanType, ApiClient::new);
    }

    DefectDojoPublisher(final String artifact, final String scanType, @lombok.NonNull final ApiClientFactory clientFactory) {
        this.artifact = artifact;
        this.scanType = scanType;
        this.clientFactory = clientFactory;
        descriptor = getDescriptor();
    }
    
    /**
     * This method is called whenever the build step is executed.
     *
     * @param run a build this is running as a part of
     * @param workspace a workspace to use for any file operations
     * @param env environment variables applicable to this step
     * @param launcher a way to start processes
     * @param listener a place to send output
     * @throws InterruptedException if the step is interrupted
     * @throws IOException if something goes wrong
     */
    @Override
    public void perform(@NonNull final Run<?, ?> run, @NonNull final FilePath workspace, @NonNull final EnvVars env, @NonNull final Launcher launcher, @NonNull final TaskListener listener) throws InterruptedException, IOException {
        final ConsoleLogger logger = new ConsoleLogger(listener.getLogger());
        final String effectiveProductName = env.expand(productName);
        final String effectiveEngagementName = env.expand(engagementName);
        final String effectiveSourceCodeUrl = env.expand(sourceCodeUrl);
        final String effectiveCommitHash = env.expand(commitHash);
        final String effectiveBranchTag = env.expand(branchTag);
        final String effectiveArtifact = env.expand(artifact);
        final String effectiveScanType = env.expand(scanType);
        // final boolean effectiveAutoCreateProduct = isEffectiveAutoCreateProducts();
        final boolean effectiveAutoCreateEngagement = isEffectiveAutoCreateEngagements();
        final boolean effectiveReupload = isEffectiveReuploadScan();
        projectIdCache = null;

        if (StringUtils.isBlank(effectiveArtifact)) {
            logger.log(Messages.Builder_Artifact_Unspecified());
            throw new AbortException(Messages.Builder_Artifact_Unspecified());
        }
        if (StringUtils.isBlank(effectiveScanType)) {
            logger.log(Messages.Builder_ScanType_Unspecified());
            throw new AbortException(Messages.Builder_ScanType_Unspecified());
        }
        if (StringUtils.isBlank(productId) && (StringUtils.isBlank(effectiveProductName))) {
            logger.log(Messages.Builder_Result_InvalidArguments());
            throw new AbortException(Messages.Builder_Result_InvalidArguments());
        }
        if (StringUtils.isBlank(engagementId) && (StringUtils.isBlank(effectiveEngagementName))) {
            logger.log(Messages.Builder_Result_InvalidArguments());
            throw new AbortException(Messages.Builder_Result_InvalidArguments());
        }

        final FilePath artifactFilePath = new FilePath(workspace, effectiveArtifact);
        if (!artifactFilePath.exists()) {
            logger.log(Messages.Builder_Artifact_NonExist(effectiveArtifact));
            throw new AbortException(Messages.Builder_Artifact_NonExist(effectiveArtifact));
        }

        final String effectiveUrl = getEffectiveUrl();
        final String effectiveApiKey = getEffectiveApiKey(run);
        logger.log(Messages.Builder_Publishing(effectiveUrl));
        final ApiClient apiClient = clientFactory.create(effectiveUrl, effectiveApiKey, logger, getEffectiveConnectionTimeout(), getEffectiveReadTimeout());

        if (!isEffectiveAutoCreateProducts() && !StringUtils.isBlank(productName) && StringUtils.isBlank(productId)) {
            productId = apiClient.getProductId(effectiveProductName);
        }
        
        if (StringUtils.isBlank(productId)) {
            logger.log(Messages.Builder_Result_ProductIdMissing());
            throw new AbortException(Messages.Builder_Result_ProductIdMissing());
        }

        if (!isEffectiveAutoCreateEngagements() && !StringUtils.isBlank(engagementName) && StringUtils.isBlank(engagementId)) {
            engagementId = apiClient.getEngagementId(productId, effectiveEngagementName);
        }
        
        if(StringUtils.isBlank(engagementId)) {
            logger.log(Messages.Builder_Result_EngagementIdMissing());
            throw new AbortException(Messages.Builder_Result_EngagementIdMissing());
        }
        
        if (StringUtils.isNotBlank(effectiveEngagementName) && effectiveAutoCreateEngagement) {
            engagementId = apiClient.createEngagement(effectiveEngagementName, productId, effectiveSourceCodeUrl);
        }

        
        final boolean uploadResult = apiClient.upload(productId, engagementId, effectiveSourceCodeUrl, 
                    effectiveBranchTag, effectiveCommitHash, artifactFilePath, scanType, effectiveReupload);

        if (!uploadResult) {
            throw new AbortException(Messages.Builder_Upload_Failed());
        }

        logger.log(Messages.Builder_Success(String.format("%s/engagements/%s", getEffectiveUrl(), StringUtils.isNotBlank(engagementId) ? engagementId : StringUtils.EMPTY)));

    }

    /**
     *
     * @return A Descriptor Implementation
     */
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     * restore transient fields after deserialization
     *
     * @return this
     * @throws java.io.ObjectStreamException never
     */
    private Object readResolve() throws java.io.ObjectStreamException {
        if (clientFactory == null) {
            clientFactory = ApiClient::new;
        }
        if (descriptor == null) {
            descriptor = getDescriptor();
        }
        overrideGlobals = StringUtils.isNotBlank(defectDojoUrl) || StringUtils.isNotBlank(defectDojoApiKey) || autoCreateProducts != null;
        return this;
    }

    /**
     * deletes values of optional fields if they are not needed/active before
     * serialization
     *
     * @return this
     * @throws java.io.ObjectStreamException never
     */
    private Object writeReplace() throws java.io.ObjectStreamException {
        if (!overrideGlobals) {
            defectDojoUrl = null;
            defectDojoApiKey = null;
            autoCreateProducts = null;
            autoCreateEngagements = null;
            defectDojoConnectionTimeout = null;
            defectDojoReadTimeout = null;
        }
        if (!isEffectiveAutoCreateProducts()) {
            productName = null;
        }
        if (!isEffectiveAutoCreateEngagements()) {
            engagementName = null;
        }
        return this;
    }

    /**
     * @return effective defectDojoUrl
     */
    @NonNull
    private String getEffectiveUrl() {
        String url = Optional.ofNullable(PluginUtil.parseBaseUrl(defectDojoUrl)).orElseGet(descriptor::getDefectDojoUrl);
        return Optional.ofNullable(url).orElse(StringUtils.EMPTY);
    }

    /**
     * resolves credential-id to actual api-key
     *
     * @param run needed for credential retrieval
     * @return effective api-key
     */
    @NonNull
    private String getEffectiveApiKey(final @NonNull Run<?, ?> run) {
        final String credId = Optional.ofNullable(StringUtils.trimToNull(defectDojoApiKey)).orElseGet(descriptor::getDefectDojoApiKey);
        if (credId != null) {
            StringCredentials cred = CredentialsProvider.findCredentialById(credId, StringCredentials.class, run);
            return Optional.ofNullable(CredentialsProvider.track(run, cred)).map(StringCredentials::getSecret).map(Secret::getPlainText).orElse(credId);
        } else {
            return StringUtils.EMPTY;
        }
    }

    /**
     * @return effective autoCreateProducts
     */
    public boolean isEffectiveAutoCreateProducts() {
        return Optional.ofNullable(autoCreateProducts).orElseGet(descriptor::isDefectDojoAutoCreateProducts);
    }

    /**
     * @return effective autoCreateEngagements
     */
    public boolean isEffectiveAutoCreateEngagements() {
        return Optional.ofNullable(autoCreateEngagements).orElseGet(descriptor::isDefectDojoAutoCreateEngagements);
    }

    /**
     * @return effective reuploadScan
     */
    public boolean isEffectiveReuploadScan() {
        return Optional.ofNullable(defectDojoReuploadScan).orElseGet(descriptor::isDefectDojoReuploadScan);
    }

    /**
     * @return effective defectDojoConnectionTimeout
     */
    @NonNull
    private int getEffectiveConnectionTimeout() {
        return Optional.ofNullable(defectDojoConnectionTimeout).filter(v -> v >= 0).orElseGet(descriptor::getDefectDojoConnectionTimeout);
    }

    /**
     * @return effective defectDojoReadTimeout
     */
    @NonNull
    private int getEffectiveReadTimeout() {
        return Optional.ofNullable(defectDojoReadTimeout).filter(v -> v >= 0).orElseGet(descriptor::getDefectDojoReadTimeout);
    }
}
