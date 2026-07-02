package dev.vox.lss.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaSupportTest {

    @Test
    void detectFalseWhenClassAbsent() {
        assertFalse(FoliaSupport.detect(name -> { throw new ClassNotFoundException(name); }));
    }

    @Test
    void detectTrueWhenClassPresent() {
        assertTrue(FoliaSupport.detect(name -> Object.class));
    }

    @Test
    void unitTestClasspathIsNotFolia() {
        assertFalse(FoliaSupport.IS_FOLIA, "paper-api test classpath must not look like Folia");
    }
}
