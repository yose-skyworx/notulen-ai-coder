package com.ilkeiapps.skyworx.notulenai.utility;

import java.util.ResourceBundle;

/**
 * Small, focused helpers (house style): user-facing error message lookup (from
 * {@code error_message.properties}) and pagination math.
 */
public final class Utility {

	private static final ResourceBundle ERROR_MESSAGES = ResourceBundle.getBundle("error_message");

	private Utility() {
	}

	/** Returns the localized message for {@code key}, or the key itself when it is not defined. */
	public static String getErrorMessage(String key) {
		if (key == null || !ERROR_MESSAGES.containsKey(key)) {
			return key;
		}
		return ERROR_MESSAGES.getString(key);
	}

	/** Resolves a request page size, falling back to {@link Constant#PER_PAGE} when absent or non-positive. */
	public static int getPerPage(Integer perPage) {
		if (perPage == null || perPage <= 0) {
			return Constant.PER_PAGE;
		}
		return perPage;
	}

	/** Zero-based row offset for the given 1-based page number and page size. */
	public static int getOffsetPage(Integer page, int perPage) {
		if (page == null || page <= 1) {
			return 0;
		}
		return (page - 1) * perPage;
	}

	/** Number of pages for {@code allData} rows at the given page size. */
	public static int getMaxPage(long allData, int perPage) {
		if (allData < 1) {
			return 0;
		}
		int tmp = (int) (allData / perPage);
		if (allData % perPage > 0) {
			tmp++;
		}
		return tmp;
	}
}
