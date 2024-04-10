package io.jenkins.plugins.configuration;

import io.jenkins.plugins.DefectDojo.DescriptorImpl;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;

import org.junit.ClassRule;
import org.junit.Test;

import static io.jenkins.plugins.casc.misc.Util.getUnclassifiedRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.assertj.core.api.Assertions.assertThat;

public class ConfigurationAsCodeTest {

    @ClassRule
    @ConfiguredWithCode("defectdojo_test_config.yml")
    public static JenkinsConfiguredWithCodeRule r = new JenkinsConfiguredWithCodeRule();

    @Test
    public void shouldSupportConfigurationAsCode() throws Exception {
        DescriptorImpl descriptor = r.jenkins.getDescriptorByType(DescriptorImpl.class);

        assertThat(descriptor)
                .returns("https://example.org/defectdojo", DescriptorImpl::getDefectDojoUrl)
                .returns("R4nD0m", DescriptorImpl::getDefectDojoApiKey)
                .returns(false, DescriptorImpl::isDefectDojoAutoCreateProducts)
                .returns(false, DescriptorImpl::isDefectDojoAutoCreateEngagements)
                .returns(false, DescriptorImpl::isDefectDojoReuploadScan)
                .returns(1, DescriptorImpl::getDefectDojoConnectionTimeout)
                .returns(3, DescriptorImpl::getDefectDojoReadTimeout)
                ;
    }

    @Test
    public void shouldSupportConfigurationExport() throws Exception {
        var registry = ConfiguratorRegistry.get();
        var context = new ConfigurationContext(registry);
        var yourAttribute = getUnclassifiedRoot(context).get("defectDojoPublisher");

        var exported = toYamlString(yourAttribute);

        try (var res = getClass().getClassLoader().getResourceAsStream("defectdojo_test_config_exported.yml")) {
            var expected = new String(res.readAllBytes());
            assertThat(exported).isEqualToNormalizingNewlines(expected);
        }
    }
}
