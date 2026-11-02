package com.feijimiao.xianyuassistant.security;

import com.feijimiao.xianyuassistant.security.CredentialCipher;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 敏感凭据字段（Cookie/Token）的加密 TypeHandler。
 * <p>
 * 写库时通过 {@link CredentialCipher#encrypt(String)} 加密为 AESGCM 密文；
 * 读库时通过 {@link CredentialCipher#decrypt(String)} 解密，兼容历史明文数据（解密失败原样返回）。
 * </p>
 */
public class EncryptedStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, CredentialCipher.encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return CredentialCipher.decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return CredentialCipher.decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return CredentialCipher.decrypt(cs.getString(columnIndex));
    }
}
