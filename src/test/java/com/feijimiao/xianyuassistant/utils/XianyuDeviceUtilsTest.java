package com.feijimiao.xianyuassistant.utils;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class XianyuDeviceUtilsTest {

    @Test
    void uuidValuesRemainUniqueWhenGeneratedConcurrently() {
        Set<String> values = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 2_000).parallel()
                .mapToObj(ignored -> XianyuDeviceUtils.generateUuid())
                .forEach(values::add);

        assertThat(values).hasSize(2_000);
        assertThat(values).allMatch(value -> value.matches("-\\d+1"));
    }
}
