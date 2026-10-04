package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.McDeviceBranding;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MeinConnect fork: per-device kiosk branding (table mcDeviceBranding). */
public interface McDeviceBrandingMapper {

    @Select({"SELECT * FROM mcDeviceBranding WHERE deviceId = #{deviceId}"})
    McDeviceBranding findByDeviceId(@Param("deviceId") Integer deviceId);

    @Select({"SELECT b.* FROM mcDeviceBranding b JOIN devices d ON d.id = b.deviceId WHERE d.customerId = #{customerId}"})
    List<McDeviceBranding> listByCustomer(@Param("customerId") int customerId);

    @Insert({"INSERT INTO mcDeviceBranding (deviceId, title, logoUrl, updatedAt) " +
            "VALUES (#{deviceId}, #{title}, #{logoUrl}, #{updatedAt}) " +
            "ON CONFLICT (deviceId) DO UPDATE SET title = EXCLUDED.title, logoUrl = EXCLUDED.logoUrl, " +
            "updatedAt = EXCLUDED.updatedAt"})
    void upsert(McDeviceBranding branding);

    @Delete({"DELETE FROM mcDeviceBranding WHERE deviceId = #{deviceId}"})
    void delete(@Param("deviceId") Integer deviceId);
}
