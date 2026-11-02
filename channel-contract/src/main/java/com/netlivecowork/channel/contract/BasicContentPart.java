package com.netlivecowork.channel.contract;

import java.util.List;

/** 预览等场景只接受三类基础内容，不递归引用。 */
public sealed interface BasicContentPart extends ContentPart
    permits ContentPart.Text, ContentPart.Image, ContentPart.FilePart {
}
