package yunqi.zhibei.steward.binding.nacos.client.v3;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;

import java.util.Objects;

public final class NacosClient3Handle {

    private final ConfigService configService;
    private final NamingService namingService;

    NacosClient3Handle(ConfigService configService, NamingService namingService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.namingService = Objects.requireNonNull(namingService, "namingService");
    }

    public ConfigService configService() {
        return configService;
    }

    public NamingService namingService() {
        return namingService;
    }
}
