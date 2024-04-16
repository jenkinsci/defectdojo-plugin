/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.jenkins.plugins.DefectDojo;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

/**
 * <p>
 * Descriptor for {@link DefectDojoPublisher}. Used as a singleton. The
 * class is marked as public so that it can be accessed from views.
 * <p>
 * See
 * <code>src/main/resources/io/jenkins/plugins/DefectDojo/DefectDojoPublisher/*.jelly</code>
 * for the actual HTML fragment for the configuration screen.
 */
@Extension
@Symbol("defectDojoPublisher") // This indicates to Jenkins that this is an implementation of an extension point.
public class DescriptorImpl extends BuildStepDescriptor<Publisher> implements Serializable {

    private static final long serialVersionUID = -2018722914973282748L;

    private final transient ApiClientFactory clientFactory;

    /**
     * Specifies the base URL to DefectDojo.
     */
    @Setter(onMethod_ = {@DataBoundSetter})
    private String defectDojoUrl;

    /**
     * Specifies an API Key used for authentication (if authentication is
     * required).
     */
    @Getter(onMethod_ = {@CheckForNull})
    @Setter(onMethod_ = {@DataBoundSetter})
    private String defectDojoApiKey;

    /**
     * Specifies whether the API key provided has the PRODUCT_CREATION_UPLOAD
     * permission.
     */
    @Getter
    @Setter(onMethod_ = {@DataBoundSetter})
    private boolean defectDojoAutoCreateProducts;

    /**
     * Specifies whether the API key provided has the ENGAGEMENT_CREATION_UPLOAD
     * permission.
     */
    @Getter
    @Setter(onMethod_ = {@DataBoundSetter})
    private boolean defectDojoAutoCreateEngagements; 

    /**
     * Specifies whether the API key provided has the ENGAGEMENT_CREATION_UPLOAD
     * permission.
     */
    @Getter
    @Setter(onMethod_ = {@DataBoundSetter})
    private boolean defectDojoReuploadScan;

    /**
     * the connection-timeout in seconds for every call to DT
     */
    @Getter
    @Setter(onMethod_ = {@DataBoundSetter})
    private int defectDojoConnectionTimeout;

    /**
     * the read-timeout in seconds for every call to DT
     */
    @Getter
    @Setter(onMethod_ = {@DataBoundSetter})
    private int defectDojoReadTimeout;

    /**
     * Default constructor. Obtains the Descriptor used in
     * DependencyCheckBuilder as this contains the global Dependency-Check
     * Jenkins plugin configuration.
     */
    public DescriptorImpl() {
        this(ApiClient::new);
    }

    DescriptorImpl(@NonNull ApiClientFactory clientFactory) {
        super(DefectDojoPublisher.class);
        this.clientFactory = clientFactory;
        load();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> aClass) {
        // Indicates that this builder can be used with all kinds of project types
        return true;
    }

    /**
     * Retrieve the projects to populate the dropdown.
     *
     * @param defectDojoUrl the base URL to DefectDojo
     * @param defectDojoApiKey the API key to use for authentication
     * @param item used to lookup credentials in job config. ignored in global
     * @return ListBoxModel
     */
    @POST
    @SuppressWarnings("lgtm[jenkins/credentials-fill-without-permission-check]")
    public ListBoxModel doFillProductIdItems(@QueryParameter final String defectDojoUrl, @QueryParameter final String defectDojoApiKey, @AncestorInPath @Nullable final Item item) {
        final ListBoxModel projects = new ListBoxModel();
        try {
            // url may come from instance-config. if empty, then take it from global config (this)
            final String url = Optional.ofNullable(PluginUtil.parseBaseUrl(defectDojoUrl)).orElseGet(this::getDefectDojoUrl);
            // api-key may come from instance-config. if empty, then take it from global config (this)
            final Secret apiKey = lookupApiKey(Optional.ofNullable(StringUtils.trimToNull(defectDojoApiKey)).orElseGet(this::getDefectDojoApiKey), item);
            final ApiClient apiClient = getClient(url, apiKey);
            final List<ListBoxModel.Option> options = apiClient.getProducts().stream()
                    .map(p -> new ListBoxModel.Option(p.getString("name"), p.getString("id")))
                    .sorted(Comparator.comparing(o -> o.name))
                    .collect(Collectors.toList());
            projects.add(new ListBoxModel.Option(Messages.Publisher_ProductList_Placeholder(), StringUtils.EMPTY));
            projects.addAll(options);
        } catch (ApiClientException e) {
            projects.add(Messages.Builder_Error_Products(e.getLocalizedMessage()), StringUtils.EMPTY);
        }
        return projects;
    }

     /**
     * Retrieve the projects to populate the dropdown.
     *
     * @param defectDojoUrl the base URL to DefectDojo
     * @param defectDojoApiKey the API key to use for authentication
     * @param item used to lookup credentials in job config. ignored in global
     * @return ListBoxModel
     */
    @POST
    @SuppressWarnings("lgtm[jenkins/credentials-fill-without-permission-check]")
    public ListBoxModel doFillEngagementIdItems(@QueryParameter final String defectDojoUrl, @QueryParameter final String defectDojoApiKey, @QueryParameter("productId") String productId, @AncestorInPath @Nullable final Item item) {
        final ListBoxModel engagements = new ListBoxModel();
        try {
            // url may come from instance-config. if empty, then take it from global config (this)
            final String url = Optional.ofNullable(PluginUtil.parseBaseUrl(defectDojoUrl)).orElseGet(this::getDefectDojoUrl);
            // api-key may come from instance-config. if empty, then take it from global config (this)
            final Secret apiKey = lookupApiKey(Optional.ofNullable(StringUtils.trimToNull(defectDojoApiKey)).orElseGet(this::getDefectDojoApiKey), item);
            final ApiClient apiClient = getClient(url, apiKey);
            engagements.add(new ListBoxModel.Option(Messages.Publisher_EngagementList_Placeholder(), StringUtils.EMPTY));
            if (!StringUtils.isBlank(productId)) {
                final List<ListBoxModel.Option> options = apiClient.getEngagements(productId).stream()
                    .map(p -> new ListBoxModel.Option(p.getString("name"), p.getString("id")))
                    .sorted(Comparator.comparing(o -> o.name))
                    .collect(Collectors.toList());
                engagements.addAll(options);
            }
        } catch (ApiClientException e) {
            engagements.add(Messages.Builder_Error_Products(e.getLocalizedMessage()), StringUtils.EMPTY);
        }
        return engagements;
    }

    /**
     * Retrieve the projects to populate the dropdown.
     *
     * @param defectDojoUrl the base URL to DefectDojo
     * @param defectDojoApiKey the API key to use for authentication
     * @param item used to lookup credentials in job config. ignored in global
     * @return ListBoxModel
     */
    @POST
    @SuppressWarnings("lgtm[jenkins/credentials-fill-without-permission-check]")
    public ListBoxModel doFillScanTypeItems(@QueryParameter final String defectDojoUrl, @QueryParameter final String defectDojoApiKey, @AncestorInPath @Nullable final Item item) {
        final ListBoxModel projects = new ListBoxModel();
        try {
            // url may come from instance-config. if empty, then take it from global config (this)
            final String url = Optional.ofNullable(PluginUtil.parseBaseUrl(defectDojoUrl)).orElseGet(this::getDefectDojoUrl);
            // api-key may come from instance-config. if empty, then take it from global config (this)
            final Secret apiKey = lookupApiKey(Optional.ofNullable(StringUtils.trimToNull(defectDojoApiKey)).orElseGet(this::getDefectDojoApiKey), item);
            final ApiClient apiClient = getClient(url, apiKey);
            final List<ListBoxModel.Option> options = apiClient.getScanTypes().stream()
                    .map(p -> new ListBoxModel.Option(p.getString("name")))
                    .sorted(Comparator.comparing(o -> o.name))
                    .collect(Collectors.toList());
            projects.add(new ListBoxModel.Option(Messages.Publisher_ScanTypeList_Placeholder(), StringUtils.EMPTY));
            projects.addAll(options);
        } catch (ApiClientException e) {
            projects.add(Messages.Builder_Error_Products(e.getLocalizedMessage()), StringUtils.EMPTY);
        }
        return projects;
    }

    @POST
    @SuppressWarnings("lgtm[jenkins/credentials-fill-without-permission-check]")
    public ListBoxModel doFillDefectDojoApiKeyItems(@QueryParameter final String credentialsId, @AncestorInPath final Item item) {
        StandardListBoxModel result = new StandardListBoxModel();
        if (item == null) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
        } else {
            if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(credentialsId);
            }
        }
        return result
                .includeEmptyValue()
                .includeAs(ACL.SYSTEM2, item, StringCredentials.class, List.of())
                .includeCurrentValue(credentialsId);
    }

    /**
     * Performs input validation when submitting the global or job config
     *
     * @param value The value of the URL as specified in the global config
     * @param item used to check permissions in job config. ignored in global
     * @return a FormValidation object
     */
    @POST
    public FormValidation doCheckDefectDojoUrl(@QueryParameter final String value, @AncestorInPath @Nullable final Item item) {
        if (item == null) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        } else {
            item.checkPermission(Item.CONFIGURE);
        }
        return PluginUtil.doCheckUrl(value);
    }

    @POST
    public FormValidation doTestConnectionGlobal(@QueryParameter final String defectDojoUrl, @QueryParameter final String defectDojoApiKey, @AncestorInPath @Nullable Item item) {
        return testConnection(defectDojoUrl, defectDojoApiKey, item);
    }

    @POST
    public FormValidation doTestConnectionJob(@QueryParameter final String defectDojoUrl, @QueryParameter final String defectDojoApiKey, @AncestorInPath @Nullable Item item) {
        return testConnection(defectDojoUrl, defectDojoApiKey, item);
    }

    /**
     * Performs an on-the-fly check of the DefectDojo URL and api key
     * parameters by making a simple call to the server and validating the
     * response code.
     *
     * @param defectDojoUrl the base URL to DefectDojo
     * @param defectDojoApiKey the credential-id of the API key to use for
     * authentication
     * @param autoCreateProducts if auto-create projects is enabled or not
     * @param synchronous if sync-mode is enabled or not
     * @param item used to check permission and lookup credentials
     * @return FormValidation
     */
    private FormValidation testConnection(final String defectDojoUrl, final String defectDojoApiKey, @AncestorInPath @Nullable Item item) {
        if (item == null) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        } else {
            item.checkPermission(Item.CONFIGURE);
        }
        FormValidation.Kind formValid = FormValidation.Kind.OK;
        // url may come from instance-config. if empty, then take it from global config (this)
        final String url = Optional.ofNullable(PluginUtil.parseBaseUrl(defectDojoUrl)).orElseGet(this::getDefectDojoUrl);
        // api-key may come from instance-config. if empty, then take it from global config (this)
        final Secret apiKey = lookupApiKey(Optional.ofNullable(StringUtils.trimToNull(defectDojoApiKey)).orElseGet(this::getDefectDojoApiKey), item);
        if (doCheckDefectDojoUrl(url, item).kind == formValid && apiKey != null) {
            try {
                final ApiClient apiClient = getClient(url, apiKey);
                final boolean status = apiClient.testConnection();
                if (!status) {
                    return FormValidation.error(Messages.Publisher_ConnectionTest_Error("Something went wrong"));
                }
                return  FormValidation.respond(formValid, String.format("<div class=\"%s\">%s</div>", formValid.name().toLowerCase(Locale.ENGLISH), "Connection OK"));
            } catch (ApiClientException e) {
                return FormValidation.error(e, Messages.Publisher_ConnectionTest_Error(e.getMessage()));
            }
        }
        return FormValidation.error(Messages.Publisher_ConnectionTest_InputError());
    }

    /**
     * Takes the /apply/save step in the global config and saves the JSON data.
     *
     * @param req the request
     * @param formData the form data
     * @return a boolean
     * @throws FormException an exception validating form input
     */
    @Override
    public boolean configure(final StaplerRequest req, final JSONObject formData) throws Descriptor.FormException {
        req.bindJSON(this, formData);
        save();
        return super.configure(req, formData);
    }

    /**
     * @return global configuration for defectDojoUrl
     */
    @CheckForNull
    public String getDefectDojoUrl() {
        return PluginUtil.parseBaseUrl(defectDojoUrl);
    }

    private ApiClient getClient(final String baseUrl, final Secret apiKey) {
        return clientFactory.create(baseUrl, apiKey, new ConsoleLogger(), Math.max(defectDojoConnectionTimeout, 0), Math.max(defectDojoReadTimeout, 0));
    }

    private Secret lookupApiKey(final String credentialId, final Item item) {
        return CredentialsProvider.lookupCredentialsInItem(StringCredentials.class, item, ACL.SYSTEM2, List.of()).stream()
                .filter(c -> c.getId().equals(credentialId))
                .map(StringCredentials::getSecret)
                .findFirst().orElse(null);
    }
}
