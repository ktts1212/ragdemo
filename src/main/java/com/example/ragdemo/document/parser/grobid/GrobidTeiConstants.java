package com.example.ragdemo.document.parser.grobid;

/**
 * GROBID TEI XML 解析相关常量。
 */
public final class GrobidTeiConstants {

    private GrobidTeiConstants() {
    }

    /** TEI 默认 namespace */
    public static final String TEI_NAMESPACE_URI = "http://www.tei-c.org/ns/1.0";

    /** XPath 中使用的 tei prefix */
    public static final String TEI_PREFIX = "tei";

    /** 领域模型结构版本（项目内部版本号，不从 GROBID 参数伪造） */
    public static final String STRUCTURE_VERSION = "academic-document-v1";
}
