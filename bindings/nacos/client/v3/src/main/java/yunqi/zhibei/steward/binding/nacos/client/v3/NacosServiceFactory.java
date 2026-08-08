package yunqi.zhibei.steward.binding.nacos.client.v3;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;

import java.util.Properties;

interface NacosServiceFactory {

    ConfigService createConfigService(Properties properties) throws Exception;

    NamingService createNamingService(Properties properties) throws Exception;
}
