/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.jenkins.plugins.DefectDojo;

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
public final class ProductProperties extends AbstractDescribableImpl<ProductProperties> implements Serializable {

    private static final long serialVersionUID = 5343757342998957784L;

    @Extension
    public static class DescriptorImpl extends Descriptor<ProductProperties> {

        /**
         * Retrieve the products to populate the dropdown.
         *
         * @param defectDojoUrl the base URL to DefectDojo
         * @param defectDojoCredentialsId the API key to use for authentication
         * @param item used to lookup credentials in job config
         * @return ListBoxModel
         */
        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public ListBoxModel doFillParentIdItems(@RelativePath("..") @QueryParameter final String defectDojoUrl, @RelativePath("..") @QueryParameter final String defectDojoCredentialsId, @AncestorInPath @Nullable final Item item) {
            io.jenkins.plugins.DefectDojo.DescriptorImpl pluginDescriptor = Jenkins.get().getDescriptorByType(io.jenkins.plugins.DefectDojo.DescriptorImpl.class);
            return pluginDescriptor.doFillProductIdItems(defectDojoUrl, defectDojoCredentialsId, item);
        }
    }
}
