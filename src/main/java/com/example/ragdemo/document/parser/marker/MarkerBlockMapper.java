package com.example.ragdemo.document.parser.marker;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.FootnoteBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.document.marker.raw.MarkerBlock;
import com.example.ragdemo.document.marker.raw.MarkerBlockType;

import lombok.extern.slf4j.Slf4j;

/**
 * Marker block → 领域 block 的结构映射（skill §10：忠实映射，不做语义猜测）。
 * <p>
 * 允许：HTML→text、id 保留、页码、Figure/Table/Formula 构建、base64 外部化。
 * 禁止：References 修复、跨页合并、Caption 猜测、Back matter 分类（属 Repair/Cleaner）。
 */
@Slf4j
@Component
public class MarkerBlockMapper {

    private final MarkerTextExtractor textExtractor;

    public MarkerBlockMapper(MarkerTextExtractor textExtractor) {
        this.textExtractor = textExtractor;
    }

    public ParagraphBlock toParagraph(MarkerBlock raw, int order, PageRange pageRange) {
        return ParagraphBlock.builder()
                .id(raw.getId())
                .sourceBlockId(raw.getId())
                .sourceBlockIds(new ArrayList<>(List.of(raw.getId())))
                .text(textExtractor.extractText(raw.getHtml()))
                .order(order)
                .pageRange(pageRange)
                .build();
    }

    public FigureBlock toFigure(MarkerBlock raw, int order, PageRange pageRange, MarkerParseContext ctx) {
        String imageKey = externalizeFirstImage(raw, ctx);
        return FigureBlock.builder()
                .id(raw.getId())
                .sourceBlockIds(new ArrayList<>(List.of(raw.getId())))
                .caption(textExtractor.extractText(raw.getHtml()))
                .order(order)
                .pageRange(pageRange)
                .imageKey(imageKey)
                .build();
    }

    public TableBlock toTable(MarkerBlock raw, int order, PageRange pageRange) {
        String html = raw.getHtml();
        return TableBlock.builder()
                .id(raw.getId())
                .sourceBlockIds(new ArrayList<>(List.of(raw.getId())))
                .html(html)
                .content(textExtractor.extractTableText(html))
                .order(order)
                .pageRange(pageRange)
                .build();
    }

    public CaptionBlock toCaption(MarkerBlock raw, int order, PageRange pageRange) {
        return CaptionBlock.builder()
                .id(raw.getId())
                .sourceBlockId(raw.getId())
                .text(textExtractor.extractText(raw.getHtml()))
                .order(order)
                .pageRange(pageRange)
                .build();
    }

    public FormulaBlock toFormula(MarkerBlock raw, int order, PageRange pageRange) {
        return FormulaBlock.builder()
                .id(raw.getId())
                .sourceBlockId(raw.getId())
                .formula(textExtractor.extractText(raw.getHtml()))
                .order(order)
                .pageRange(pageRange)
                .build();
    }

    public FootnoteBlock toFootnote(MarkerBlock raw, int order, PageRange pageRange) {
        return FootnoteBlock.builder()
                .id(raw.getId())
                .sourceBlockId(raw.getId())
                .text(textExtractor.extractText(raw.getHtml()))
                .order(order)
                .pageRange(pageRange)
                .build();
    }

    public NoiseBlock toNoise(MarkerBlock raw, int order, PageRange pageRange, String noiseType) {
        return NoiseBlock.builder()
                .id(raw.getId())
                .sourceBlockId(raw.getId())
                .text(textExtractor.extractText(raw.getHtml()))
                .order(order)
                .pageRange(pageRange)
                .noiseType(noiseType)
                .build();
    }

    public ListBlock toList(MarkerBlock raw, int order, PageRange pageRange) {
        List<String> items = new ArrayList<>();
        // 优先使用 children 中的 ListItem
        if (raw.getChildren() != null) {
            for (MarkerBlock child : raw.getChildren()) {
                if (MarkerBlockType.from(child.getBlockType()) == MarkerBlockType.LIST_ITEM) {
                    String text = textExtractor.extractText(child.getHtml());
                    if (!text.isBlank()) {
                        items.add(text);
                    }
                }
            }
        }
        // fallback：从 list HTML 的 <li> 提取
        if (items.isEmpty()) {
            items = textExtractor.extractListItems(raw.getHtml());
        }
        return ListBlock.builder()
                .id(raw.getId())
                .sourceBlockId(raw.getId())
                .items(items)
                .order(order)
                .pageRange(pageRange)
                .build();
    }

    // ==================== 图片外部化 ====================

    /**
     * base64 → decode → storage.store → imageKey。
     * 绝不让 base64 进入领域模型。
     */
    private String externalizeFirstImage(MarkerBlock raw, MarkerParseContext ctx) {
        Map<String, String> images = raw.getImages();
        if (images == null || images.isEmpty()) {
            return null;
        }
        Map.Entry<String, String> first = images.entrySet().iterator().next();
        try {
            DecodedImage decoded = decodeImage(first.getValue());
            if (decoded.bytes().length == 0) {
                return null;
            }
            return ctx.getImageStorage().store(ctx.getDocumentId(), raw.getId(),
                    decoded.bytes(), decoded.contentType());
        } catch (Exception e) {
            log.warn("Marker 图片外部化失败: blockId={}, reason={}", raw.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 支持 dataURI（data:image/png;base64,...）与纯 base64 两种形式。
     */
    private DecodedImage decodeImage(String value) {
        if (value == null || value.isBlank()) {
            return new DecodedImage(new byte[0], null);
        }
        String contentType = null;
        String base64 = value;
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma > 0) {
                String meta = value.substring(5, comma);   // e.g. image/png;base64
                contentType = meta.split(";")[0];
                base64 = value.substring(comma + 1);
            }
        }
        byte[] bytes = Base64.getMimeDecoder().decode(base64);
        return new DecodedImage(bytes, contentType);
    }

    private record DecodedImage(byte[] bytes, String contentType) {
    }
}
