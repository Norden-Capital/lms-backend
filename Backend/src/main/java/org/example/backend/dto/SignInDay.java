package org.example.backend.dto;

/**
 * One day of somebody's sign-ins, for the year chart on their user page.
 * Only days with something on them are sent; the chart fills in the rest.
 *
 * @param date   YYYY-MM-DD, in the business time zone
 * @param ok     times they signed in
 * @param failed wrong password or code, tried on their account
 */
public record SignInDay(String date, long ok, long failed) {
}
