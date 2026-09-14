package com.example.ragdemo.document.domain;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 解析器信息：记录 AcademicDocument 由哪个解析器、哪个版本生成。
 * <p>
 * 用于 Debug、Parser Benchmark、解析器替换和数据版本追踪。
 * 未来 parserType = "MINERU" 时无需修改 AcademicDocument。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParserInfo {

    /** 解析器类型，如 "GROBID" / "MINERU" */
    private String parserType;

    /** 解析器具体版本 */
    private String parserVersion;

    /** 领域模型结构版本，如 "academic-document-v1" */
    private String structureVersion;

    /** 解析完成时间 */
    private Instant parsedAt;
}
