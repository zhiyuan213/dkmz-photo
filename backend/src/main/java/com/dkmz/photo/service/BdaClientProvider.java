package com.dkmz.photo.service;

import com.tencentcloudapi.bda.v20200324.BdaClient;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * BdaClient 共享提供者：分割（SegmentPortraitPic）与自定义分割
 * （SegmentCustomizedPortraitPic）共用一个客户端实例。
 *
 * 环境变量（不配置 = 云端能力整体关闭，接口明确返回「未开通」）：
 *   BDAB_ENABLED          总开关
 *   BDAB_SECRET_ID/_KEY   腾讯云 API 密钥（CAM 子账号仅授权 bda）
 *   BDAB_REGION           仅 ap-beijing / ap-guangzhou / ap-shanghai
 *   BDAB_ENDPOINT         可选；容器直连 https 报 PKIX 时试内网域名
 */
@Component
public class BdaClientProvider {

    private static final Logger log = LoggerFactory.getLogger(BdaClientProvider.class);

    @Value("${photo.bda.enabled:false}")
    private boolean enabled;

    @Value("${photo.bda.secret-id:}")
    private String secretId;

    @Value("${photo.bda.secret-key:}")
    private String secretKey;

    @Value("${photo.bda.region:ap-guangzhou}")
    private String region;

    @Value("${photo.bda.endpoint:}")
    private String endpoint;

    private volatile BdaClient client;

    public boolean isEnabled() {
        return enabled && notBlank(secretId) && notBlank(secretKey);
    }

    public BdaClient client() {
        if (this.client == null) {
            synchronized (this) {
                if (this.client == null) {
                    Credential cred = new Credential(secretId, secretKey);
                    HttpProfile http = new HttpProfile();
                    http.setReadTimeout(20);      // 秒，抠图是耗时操作
                    http.setConnTimeout(10);
                    if (notBlank(endpoint)) {
                        http.setEndpoint(endpoint.trim());
                    }
                    ClientProfile profile = new ClientProfile();
                    profile.setHttpProfile(http);
                    this.client = new BdaClient(cred, region, profile);
                    log.info("BdaClient 初始化 region={} endpoint={}",
                            region, notBlank(endpoint) ? endpoint : "(默认)");
                }
            }
        }
        return this.client;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
