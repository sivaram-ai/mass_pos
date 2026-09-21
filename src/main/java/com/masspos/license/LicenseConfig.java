package com.masspos.license;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LicenseConfig {

    @Bean
    VendorKey vendorKey() {
        return VendorKey.fromBase64(VendorKey.PUBLIC_KEY);
    }
}
