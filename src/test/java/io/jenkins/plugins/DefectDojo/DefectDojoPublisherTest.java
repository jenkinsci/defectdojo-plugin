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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.DumbSlave;
import hudson.util.Secret;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.LENIENT)
@WithJenkins
class DefectDojoPublisherTest {

    @Mock
    private Run build;

    @Mock
    private TaskListener listener;

    @Mock
    private Launcher launcher;

    private final EnvVars env = new EnvVars("my.var", "my.value");

    @Mock
    private Job job;

    @Mock
    private ApiClient client;

    @Rule
    private JenkinsRule jenkinsRule;

    private final ApiClientFactory clientFactory = (url, apiKey, logger, connTimeout, readTimeout) -> client;
    private final String apikeyId = "api-key-id";
    private final Secret apikey = Secret.fromString("api-key");
    private final String scanType = "s-type";

    @BeforeEach
    void setup(JenkinsRule r) throws ApiClientException, IOException {
        this.jenkinsRule = r;
        when(listener.getLogger()).thenReturn(System.err);

        CredentialsProvider.lookupStores(r.jenkins)
                .iterator()
                .next()
                .addCredentials(
                        Domain.global(),
                        new StringCredentialsImpl(
                                CredentialsScope.GLOBAL, apikeyId, "DefectDojoPublisherTest", apikey));

        // needed for credential tracking
        when(job.getParent()).thenReturn(r.jenkins);
        when(job.getName()).thenReturn("u-drive-me-crazy");
        when(job.getFullName()).thenReturn("/u-drive-me-crazy");
        when(build.getParent()).thenReturn(job);
        when(build.getNumber()).thenReturn(1);
    }

    @Test
    void testPerformPrechecks(@TempDir Path tmpWork) throws IOException {
        when(listener.getLogger()).thenReturn(System.err);
        FilePath workDir = new FilePath(tmpWork.toFile());

        // artifact missing
        final DefectDojoPublisher uut1 = new DefectDojoPublisher("", scanType, clientFactory);
        assertThatCode(() -> uut1.perform(build, workDir, env, launcher, listener))
                .isInstanceOf(AbortException.class)
                .hasMessage(Messages.Builder_Artifact_Unspecified());

        File artifact = tmpWork.resolve("report.xml").toFile();
        artifact.createNewFile();

        // scan type missing
        final DefectDojoPublisher uut2 = new DefectDojoPublisher(artifact.getName(), "", clientFactory);
        assertThatCode(() -> uut2.perform(build, workDir, env, launcher, listener))
                .isInstanceOf(AbortException.class)
                .hasMessage(Messages.Builder_ScanType_Unspecified());

        // missing engagementId / productId or engagementName / productName
        final DefectDojoPublisher uut4 = new DefectDojoPublisher(artifact.getName(), scanType, clientFactory);
        assertThatCode(() -> uut4.perform(build, workDir, env, launcher, listener))
                .isInstanceOf(AbortException.class)
                .hasMessage(Messages.Builder_Result_InvalidArguments());

        // engagementId missing
        final DefectDojoPublisher uut3 = new DefectDojoPublisher(artifact.getName(), scanType, clientFactory);
        uut3.setProductId("pid-1");
        uut3.setEngagementName("e-name");
        assertThatCode(() -> uut3.perform(build, workDir, env, launcher, listener))
                .isInstanceOf(AbortException.class)
                .hasMessage(Messages.Builder_Result_EngagementIdMissing());

        // productId missing
        final DefectDojoPublisher uut6 = new DefectDojoPublisher(artifact.getName(), scanType, clientFactory);
        uut6.setProductName("p-name");
        uut6.setEngagementName("eid-1");
        assertThatCode(() -> uut6.perform(build, workDir, env, launcher, listener))
                .isInstanceOf(AbortException.class)
                .hasMessage(Messages.Builder_Result_ProductIdMissing());

        // file not within workdir
        final DefectDojoPublisher uut5 = new DefectDojoPublisher("foo", scanType, clientFactory);
        uut5.setProductId("pid-1");
        uut5.setEngagementId("eid-1");
        assertThatCode(() -> uut5.perform(build, workDir, env, launcher, listener))
                .isInstanceOf(AbortException.class)
                .hasMessage(Messages.Builder_Artifact_NonExist("foo"));
    }

    @Test
    void testRunOnAgent(@TempDir Path tmpWork) throws Exception {

        DumbSlave agent = jenkinsRule.createSlave();
        jenkinsRule.waitOnline(agent);
        Node node = agent.toComputer().getNode();
        assertNotNull(node);

        File artifact = tmpWork.resolve("report.xml").toFile();
        artifact.createNewFile();

        FreeStyleProject project = jenkinsRule.createFreeStyleProject();
        project.setAssignedNode(agent);
        project.getPublishersList().add(new DefectDojoPublisher(artifact.getName(), scanType, clientFactory));

        // Run the build on the agent
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkinsRule.waitUntilNoActivity();

        // Validate the build log
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains(Messages.Publisher_Agent_Anouncement()));
    }
}
