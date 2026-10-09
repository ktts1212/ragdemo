package com.example.ragdemo.document.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 本地文件系统图片存储（测试 / 本地开发默认）。
 * <p>
 * 返回的 key 是相对路径（不含 baseDir 绝对路径），避免本地路径泄漏到领域模型。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.image.storage", havingValue = "local", matchIfMissing = true)
public class LocalFigureImageStorage implements FigureImageStorage {

    private final Path baseDir;

    public LocalFigureImageStorage(
            @Value("${rag.image.local-dir:target/figure-images}") String dir) {
        this.baseDir = Path.of(dir);
    }

    @Override
    public String store(String documentId, String blockId, byte[] imageBytes, String contentType) {
        String key = ImageKeyUtils.buildKey(documentId, blockId, contentType);
        Path target = baseDir.resolve(key);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, imageBytes);
            log.debug("图片已保存本地: key={}, size={} bytes", key, imageBytes.length);
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("本地图片保存失败: " + key, e);
        }
    }
}
