package com.masspos.receipt;

import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** @param footer lines printed centred at the end of every receipt, e.g. the exchange policy */
@Validated
@ConfigurationProperties(prefix = "pos.receipt")
public record ReceiptProperties(List<@Size(max = 200) String> footer) {

    public ReceiptProperties {
        footer = footer == null ? List.of()
                : footer.stream().filter(line -> line != null && !line.isBlank()).map(String::trim).toList();
    }
}
