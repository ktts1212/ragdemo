package com.example.ragdemo.service.serviceImpl;

import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 文档解析服务实现：根据文件扩展名选择 LangChain4j 解析器
 */
@Service
public class DocumentParserServiceImpl implements DocumentParserService {

    @Override
    public String parse(InputStream inputStream, String fileExt) {
        return null;
    }
}
