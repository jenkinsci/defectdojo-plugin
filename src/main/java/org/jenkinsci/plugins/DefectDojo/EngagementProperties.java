package org.jenkinsci.plugins.DefectDojo;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.ListBoxModel;
import java.io.Serializable;
import jenkins.model.Jenkins;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

@Getter
@lombok.NoArgsConstructor(onConstructor_ = {@DataBoundConstructor})
@EqualsAndHashCode(callSuper = false, doNotUseGetters = true)
public class EngagementProperties extends AbstractDescribableImpl<EngagementProperties> implements Serializable {

    private static final long serialVersionUID = 5343757342998957784L;

    @Extension
    public static class DescriptorImpl extends Descriptor<EngagementProperties> {

        /**
         * Retrieve the projects to populate the dropdown.
         *
         * @param defectDojoUrl the base URL to DefectDojo
         * @param defectDojoApiKey the API key to use for authentication
         * @param item used to lookup credentials in job config
         * @return ListBoxModel
         */
        @POST
        public ListBoxModel doFillParentIdItems(@RelativePath("..") @QueryParameter final String defectDojoUrl, @RelativePath("..") @QueryParameter final String defectDojoApiKey, @QueryParameter("productId") String product, @AncestorInPath @Nullable final Item item) {
            org.jenkinsci.plugins.DefectDojo.DescriptorImpl pluginDescriptor = Jenkins.get().getDescriptorByType(org.jenkinsci.plugins.DefectDojo.DescriptorImpl.class);
            return pluginDescriptor.doFillEngagementIdItems(defectDojoUrl, defectDojoApiKey, product, item);
        }
    }
}
