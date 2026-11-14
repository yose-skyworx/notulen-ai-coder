package com.ilkeiapps.skyworx.notulenai.bean.enums;

/**
 * How a file changed in the reviewed commit, mapped from the GitHub commit {@code files[].status}
 * value. {@code RENAMED} rows keep both the old and the new path so the correct source snapshot is
 * used for each side.
 */
public enum FileChangeStatus {

	ADDED, MODIFIED, REMOVED, RENAMED
}
