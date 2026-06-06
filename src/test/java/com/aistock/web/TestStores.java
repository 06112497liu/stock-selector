package com.aistock.web;

import com.aistock.storage.ParamsStore;
import com.aistock.storage.Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试用账本工具:在 tmp 目录建空 SQLite {@link Store},
 * 避免单测污染 {@code ./.cache} 真实账本,且默认空持仓(不联网)。
 */
final class TestStores {

    private TestStores() {
    }

    static Store tmp(String name) {
        try {
            Path dir = Files.createTempDirectory("ledger-test-" + name);
            return new Store(dir.resolve(name + "_ledger.sqlite").toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** tmp 空策略参数库(不污染真实 .cache)。 */
    static ParamsStore tmpParams(String name) {
        try {
            Path dir = Files.createTempDirectory("params-test-" + name);
            return new ParamsStore(dir.resolve(name + "_params.sqlite").toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
