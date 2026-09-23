package com.lian.aicode.manager;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.lian.aicode.config.OssClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OssManagerTest {

    @Mock
    private ObjectProvider<OSSClient> ossClientProvider;

    @Mock
    private OSSClient ossClient;

    private OssManager ossManager;

    @BeforeEach
    void setUp() {
        OssClientProperties properties = new OssClientProperties();
        properties.setEnabled(true);
        properties.setBucket("learning-bucket");
        properties.setPublicBaseUrl("https://images.example.test/assets/");
        when(ossClientProvider.getIfAvailable()).thenReturn(ossClient);
        ossManager = new OssManager(ossClientProvider, properties);
    }

    @Test
    void skipsUrlsOutsideConfiguredDomainOrApplicationPrefix() {
        assertThat(ossManager.deleteByUrl("https://external.example/image.jpg", 42L)).isFalse();
        assertThat(ossManager.deleteByUrl(
                "https://images.example.test/assets/app-covers/7/v1/cover.jpg", 42L)).isFalse();
        verifyNoInteractions(ossClient);
    }

    @Test
    void deletesOnlyCoverObjectBelongingToRequestedApp() {
        assertThat(ossManager.deleteByUrl(
                "https://images.example.test/assets/app-covers/42/v3/cover.jpg", 42L)).isTrue();

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(ossClient).deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("learning-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("app-covers/42/v3/cover.jpg");
    }
}
