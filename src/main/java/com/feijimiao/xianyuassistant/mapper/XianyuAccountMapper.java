package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * 闲鱼账号Mapper
 */
@Mapper
public interface XianyuAccountMapper extends BaseMapper<XianyuAccount> {

    /**
     * 清除 AUTOINCREMENT 在 sqlite_sequence 中的历史最大 ID 记录，
     * 使删除账号后新账号可复用被释放的 ID。
     */
    @Delete("DELETE FROM sqlite_sequence WHERE name = 'xianyu_account'")
    int resetAutoincrementCounter();
}
