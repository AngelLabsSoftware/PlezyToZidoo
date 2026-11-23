package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class SecureStorageTest {

    @Test
    public void smbSecureKey_mapsSuffixes() {
        assertEquals("smb_password", SecureStorage.smbSecureKey(""));
        assertEquals("smb_password_02", SecureStorage.smbSecureKey("_02"));
        assertEquals("smb_password", SecureStorage.smbSecureKey(null));
    }

    @Test
    public void smbPlaintextKey_mapsSuffixes() {
        assertEquals("smbPassword", SecureStorage.smbPlaintextKey(""));
        assertEquals("smbPassword_02", SecureStorage.smbPlaintextKey("_02"));
        assertEquals("smbPassword", SecureStorage.smbPlaintextKey(null));
    }

    @Test
    public void smbPasswordSuffixes_coverTenSlots() {
        assertEquals(10, SecureStorage.SMB_PASSWORD_SUFFIXES.length);
        assertEquals("", SecureStorage.SMB_PASSWORD_SUFFIXES[0]);
        assertEquals("_10", SecureStorage.SMB_PASSWORD_SUFFIXES[9]);
    }

    @Test
    public void isPasswordKey_stillDetectsSmbSlots() {
        for (String suffix : SecureStorage.SMB_PASSWORD_SUFFIXES) {
            assertTrue(SettingsActivity.isPasswordKey(SecureStorage.smbPlaintextKey(suffix)));
        }
    }
}
