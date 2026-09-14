package com.example.ragdemo.infrastructure.grobid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GROBID /api/health 返回的引擎池健康信息。
 * <p>
 * 只建模当前 GROBID 版本返回的字段，未知字段忽略，容忍后续版本扩展。
 *
 * @param active 引擎池中活跃的解析引擎数
 * @param idle   空闲解析引擎数
 * @param max    引擎池上限
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GrobidHealth(
        Integer active,
        Integer idle,
        Integer max
) {
}
