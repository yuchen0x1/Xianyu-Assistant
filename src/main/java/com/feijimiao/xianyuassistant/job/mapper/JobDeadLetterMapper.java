package com.feijimiao.xianyuassistant.job.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.job.entity.JobDeadLetter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface JobDeadLetterMapper extends BaseMapper<JobDeadLetter> {
    @Select("<script>SELECT COUNT(*) FROM job_dead_letter WHERE resolved = 0 "
            + "<if test='accountIds != null'>"
            + "<choose><when test='accountIds.isEmpty()'>AND 1 = 0</when>"
            + "<otherwise>AND account_id IN "
            + "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</otherwise></choose></if></script>")
    long countUnresolvedByAccountIds(@Param("accountIds") List<Long> accountIds);
}
