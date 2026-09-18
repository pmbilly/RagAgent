package com.ragagent.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.session.domain.TemporaryDocument;
import org.apache.ibatis.annotations.Mapper;

/** 附件表 MP 映射（对照 gorm 的 temporary_documents 操作面）。 */
@Mapper
public interface TemporaryDocumentMapper extends BaseMapper<TemporaryDocument> {
}
