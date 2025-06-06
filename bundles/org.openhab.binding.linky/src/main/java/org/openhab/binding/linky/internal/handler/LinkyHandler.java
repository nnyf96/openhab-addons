/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.linky.internal.handler;

import static org.openhab.binding.linky.internal.LinkyBindingConstants.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.linky.internal.LinkyConfiguration;
import org.openhab.binding.linky.internal.LinkyException;
import org.openhab.binding.linky.internal.api.EnedisHttpApi;
import org.openhab.binding.linky.internal.api.ExpiringDayCache;
import org.openhab.binding.linky.internal.dto.ConsumptionReport.Aggregate;
import org.openhab.binding.linky.internal.dto.ConsumptionReport.Consumption;
import org.openhab.binding.linky.internal.dto.PrmDetail;
import org.openhab.binding.linky.internal.dto.PrmInfo;
import org.openhab.binding.linky.internal.dto.UserInfo;
import org.openhab.core.i18n.LocaleProvider;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The {@link LinkyHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Gaël L'hopital - Initial contribution
 */

@NonNullByDefault
public class LinkyHandler extends BaseThingHandler {
    private static final Random randomNumbers = new Random();
    private static final int REFRESH_HOUR_OF_DAY = 1;
    private static final int REFRESH_MINUTE_OF_DAY = randomNumbers.nextInt(60);
    private static final int REFRESH_INTERVAL_IN_MIN = 120;

    private final TimeZoneProvider timeZoneProvider;
    private final Logger logger = LoggerFactory.getLogger(LinkyHandler.class);
    private final HttpClient httpClient;
    private final Gson gson;
    private final WeekFields weekFields;

    private final ExpiringDayCache<Consumption> cachedDailyData;
    private final ExpiringDayCache<Consumption> cachedPowerData;
    private final ExpiringDayCache<Consumption> cachedMonthlyData;
    private final ExpiringDayCache<Consumption> cachedYearlyData;

    private ZoneId zoneId = ZoneId.systemDefault();

    private @Nullable ScheduledFuture<?> refreshJob;
    private @Nullable EnedisHttpApi enedisApi;

    private @NonNullByDefault({}) String prmId;
    private @NonNullByDefault({}) String userId;

    private enum Target {
        FIRST,
        LAST,
        ALL
    }

    public LinkyHandler(Thing thing, LocaleProvider localeProvider, Gson gson, HttpClient httpClient,
            TimeZoneProvider timeZoneProvider) {
        super(thing);
        this.gson = gson;
        this.httpClient = httpClient;
        this.weekFields = WeekFields.of(localeProvider.getLocale());
        this.timeZoneProvider = timeZoneProvider;

        this.cachedDailyData = new ExpiringDayCache<>("daily cache", REFRESH_HOUR_OF_DAY, REFRESH_MINUTE_OF_DAY, () -> {
            LocalDate today = LocalDate.now();
            Consumption consumption = getConsumptionData(today.minusDays(15), today);

            if (consumption != null) {
                logData(consumption.aggregats.days, "Day", false, DateTimeFormatter.ISO_LOCAL_DATE, Target.ALL);
                logData(consumption.aggregats.weeks, "Week", true, DateTimeFormatter.ISO_LOCAL_DATE_TIME, Target.ALL);
                consumption = getConsumptionAfterChecks(consumption, Target.LAST);
            }
            return consumption;
        });

        this.cachedPowerData = new ExpiringDayCache<>("power cache", REFRESH_HOUR_OF_DAY, REFRESH_MINUTE_OF_DAY, () -> {
            // We request data for yesterday and the day before yesterday,
            // even if the data for the day before yesterday is not needed by the binding.
            // This is only a workaround to an API bug that will return INTERNAL_SERVER_ERROR rather
            // than the expected data with a NaN value when the data for yesterday is not yet available.
            // By requesting two days, the API is not failing and you get the expected NaN value for yesterday
            // when the data is not yet available.
            LocalDate today = LocalDate.now();
            Consumption consumption = getPowerData(today.minusDays(2), today);
            if (consumption != null) {
                logData(consumption.aggregats.days, "Day (peak)", true, DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                        Target.ALL);
                consumption = getConsumptionAfterChecks(consumption, Target.LAST);
            }
            return consumption;
        });

        this.cachedMonthlyData = new ExpiringDayCache<>("monthly cache", REFRESH_HOUR_OF_DAY, REFRESH_MINUTE_OF_DAY,
                () -> {
                    LocalDate today = LocalDate.now();
                    Consumption consumption = getConsumptionData(today.withDayOfMonth(1).minusMonths(1), today);
                    if (consumption != null) {
                        logData(consumption.aggregats.months, "Month", true, DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                                Target.ALL);
                        consumption = getConsumptionAfterChecks(consumption, Target.LAST);
                    }
                    return consumption;
                });

        this.cachedYearlyData = new ExpiringDayCache<>("yearly cache", REFRESH_HOUR_OF_DAY, REFRESH_MINUTE_OF_DAY,
                () -> {
                    LocalDate today = LocalDate.now();
                    Consumption consumption = getConsumptionData(LocalDate.of(today.getYear() - 1, 1, 1), today);
                    if (consumption != null) {
                        logData(consumption.aggregats.years, "Year", true, DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                                Target.ALL);
                        consumption = getConsumptionAfterChecks(consumption, Target.LAST);
                    }
                    return consumption;
                });
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Linky handler.");
        updateStatus(ThingStatus.UNKNOWN);

        LinkyConfiguration config = getConfigAs(LinkyConfiguration.class);
        if (config.seemsValid()) {

            if (config.timezone.isBlank()) {
                zoneId = this.timeZoneProvider.getTimeZone();
            } else {
                zoneId = ZoneId.of(config.timezone);
            }

            enedisApi = new EnedisHttpApi(config, gson, httpClient);
            scheduler.submit(() -> {
                try {
                    EnedisHttpApi api = this.enedisApi;
                    api.initialize();
                    updateStatus(ThingStatus.ONLINE);

                    updateData();

                    disconnect();

                    final LocalDateTime now = LocalDateTime.now();
                    final LocalDateTime nextDayFirstTimeUpdate = now.plusDays(1).withHour(REFRESH_HOUR_OF_DAY)
                            .withMinute(REFRESH_MINUTE_OF_DAY).truncatedTo(ChronoUnit.MINUTES);

                    refreshJob = scheduler.scheduleWithFixedDelay(this::updateData,
                            ChronoUnit.MINUTES.between(now, nextDayFirstTimeUpdate) % REFRESH_INTERVAL_IN_MIN + 1,
                            REFRESH_INTERVAL_IN_MIN, TimeUnit.MINUTES);
                } catch (LinkyException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                }
            });
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.config-error-mandatory-settings");
        }
    }

    private synchronized void updateMetaData() throws LinkyException {
        EnedisHttpApi api = this.enedisApi;
        if (api != null) {
            UserInfo userInfo = api.getUserInfo();
            PrmInfo prmInfo = api.getPrmInfo(userInfo.userProperties.internId);
            PrmDetail details = api.getPrmDetails(userInfo.userProperties.internId, prmInfo.idPrm);
            updateProperties(Map.of(USER_ID, userInfo.userProperties.internId, PUISSANCE,
                    details.situationContractuelleDtos[0].structureTarifaire().puissanceSouscrite().valeur() + " kVA",
                    PRM_ID, prmInfo.idPrm));

            prmId = thing.getProperties().get(PRM_ID);
            userId = thing.getProperties().get(USER_ID);
        }
    }

    /**
     * Request new data and updates channels
     */
    private synchronized void updateData() {
        boolean connectedBefore = isConnected();
        try {
            updateMetaData();
            updatePowerData();
            updateDailyWeeklyData();
            updateMonthlyData();
            updateYearlyData();
            if (!connectedBefore && isConnected()) {
                disconnect();
            }
        } catch (LinkyException e) {
            logger.debug("Exception occurs during data update {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private synchronized void updatePowerData() {
        if (isLinked(PEAK_POWER) || isLinked(PEAK_TIMESTAMP)) {
            cachedPowerData.getValue().ifPresentOrElse(values -> {
                Aggregate days = values.aggregats.days;
                updatekVAChannel(PEAK_POWER, days.datas.get(days.datas.size() - 1).valeur);
                updateState(PEAK_TIMESTAMP,
                        new DateTimeType(days.datas.get(days.datas.size() - 1).dateDebut.atZone(zoneId)));
            }, () -> {
                updateKwhChannel(PEAK_POWER, Double.NaN);
                updateState(PEAK_TIMESTAMP, UnDefType.UNDEF);
            });
        }
    }

    private void setCurrentAndPrevious(Aggregate periods, String currentChannel, String previousChannel) {
        double currentValue = 0.0;
        double previousValue = 0.0;
        if (!periods.datas.isEmpty()) {
            currentValue = periods.datas.get(periods.datas.size() - 1).valeur;
            if (periods.datas.size() > 1) {
                previousValue = periods.datas.get(periods.datas.size() - 2).valeur;
            }
        }
        updateKwhChannel(currentChannel, currentValue);
        updateKwhChannel(previousChannel, previousValue);
    }

    /**
     * Request new dayly/weekly data and updates channels
     */
    private synchronized void updateDailyWeeklyData() {
        if (isLinked(YESTERDAY) || isLinked(LAST_WEEK) || isLinked(THIS_WEEK)) {
            cachedDailyData.getValue().ifPresentOrElse(values -> {
                Aggregate days = values.aggregats.days;
                updateKwhChannel(YESTERDAY, days.datas.get(days.datas.size() - 1).valeur);
                setCurrentAndPrevious(values.aggregats.weeks, THIS_WEEK, LAST_WEEK);
            }, () -> {
                updateKwhChannel(YESTERDAY, Double.NaN);
                if (ZonedDateTime.now().get(weekFields.dayOfWeek()) == 1) {
                    updateKwhChannel(THIS_WEEK, 0.0);
                    updateKwhChannel(LAST_WEEK, Double.NaN);
                } else {
                    updateKwhChannel(THIS_WEEK, Double.NaN);
                }
            });
        }
    }

    /**
     * Request new monthly data and updates channels
     */
    private synchronized void updateMonthlyData() {
        if (isLinked(LAST_MONTH) || isLinked(THIS_MONTH)) {
            cachedMonthlyData.getValue().ifPresentOrElse(
                    values -> setCurrentAndPrevious(values.aggregats.months, THIS_MONTH, LAST_MONTH), () -> {
                        if (ZonedDateTime.now().getDayOfMonth() == 1) {
                            updateKwhChannel(THIS_MONTH, 0.0);
                            updateKwhChannel(LAST_MONTH, Double.NaN);
                        } else {
                            updateKwhChannel(THIS_MONTH, Double.NaN);
                        }
                    });
        }
    }

    /**
     * Request new yearly data and updates channels
     */
    private synchronized void updateYearlyData() {
        if (isLinked(LAST_YEAR) || isLinked(THIS_YEAR)) {
            cachedYearlyData.getValue().ifPresentOrElse(
                    values -> setCurrentAndPrevious(values.aggregats.years, THIS_YEAR, LAST_YEAR), () -> {
                        if (ZonedDateTime.now().getDayOfYear() == 1) {
                            updateKwhChannel(THIS_YEAR, 0.0);
                            updateKwhChannel(LAST_YEAR, Double.NaN);
                        } else {
                            updateKwhChannel(THIS_YEAR, Double.NaN);
                        }
                    });
        }
    }

    private void updateKwhChannel(String channelId, double consumption) {
        logger.debug("Update channel {} with {}", channelId, consumption);
        updateState(channelId,
                Double.isNaN(consumption) ? UnDefType.UNDEF : new QuantityType<>(consumption, Units.KILOWATT_HOUR));
    }

    private void updatekVAChannel(String channelId, double power) {
        logger.debug("Update channel {} with {}", channelId, power);
        updateState(channelId, Double.isNaN(power) ? UnDefType.UNDEF
                : new QuantityType<>(power, MetricPrefix.KILO(Units.VOLT_AMPERE)));
    }

    /**
     * Produce a report of all daily values between two dates
     *
     * @param startDay the start day of the report
     * @param endDay the end day of the report
     * @param separator the separator to be used betwwen the date and the value
     *
     * @return the report as a list of string
     */
    public synchronized List<String> reportValues(LocalDate startDay, LocalDate endDay, @Nullable String separator) {
        List<String> report = buildReport(startDay, endDay, separator);
        disconnect();
        return report;
    }

    private List<String> buildReport(LocalDate startDay, LocalDate endDay, @Nullable String separator) {
        List<String> report = new ArrayList<>();
        if (startDay.getYear() == endDay.getYear() && startDay.getMonthValue() == endDay.getMonthValue()) {
            // All values in the same month
            Consumption result = getConsumptionData(startDay, endDay.plusDays(1));
            if (result != null) {
                Aggregate days = result.aggregats.days;
                int size = (days.datas == null) ? 0 : days.datas.size();
                for (int i = 0; i < size; i++) {
                    double consumption = days.datas.get(i).valeur;
                    LocalDate day = days.datas.get(i).dateDebut.toLocalDate();
                    // Filter data in case it contains data from dates outside the requested period
                    if (day.isBefore(startDay) || day.isAfter(endDay)) {
                        continue;
                    }
                    String line = days.datas.get(i).dateDebut.format(DateTimeFormatter.ISO_LOCAL_DATE) + separator;
                    if (consumption >= 0) {
                        line += String.valueOf(consumption);
                    }
                    report.add(line);
                }
            } else {
                LocalDate currentDay = startDay;
                while (!currentDay.isAfter(endDay)) {
                    report.add(currentDay.format(DateTimeFormatter.ISO_LOCAL_DATE) + separator);
                    currentDay = currentDay.plusDays(1);
                }
            }
        } else {
            // Concatenate the report produced for each month between the two dates
            LocalDate first = startDay;
            do {
                LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
                if (last.isAfter(endDay)) {
                    last = endDay;
                }
                report.addAll(buildReport(first, last, separator));
                first = last.plusDays(1);
            } while (!first.isAfter(endDay));
        }
        return report;
    }

    private @Nullable Consumption getConsumptionData(LocalDate from, LocalDate to) {
        logger.debug("getConsumptionData from {} to {}", from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                to.format(DateTimeFormatter.ISO_LOCAL_DATE));
        EnedisHttpApi api = this.enedisApi;
        if (api != null) {
            try {
                Consumption consumption = api.getEnergyData(userId, prmId, from, to);
                updateStatus(ThingStatus.ONLINE);
                return consumption;
            } catch (LinkyException e) {
                logger.debug("Exception when getting consumption data: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            }
        }
        return null;
    }

    private @Nullable Consumption getPowerData(LocalDate from, LocalDate to) {
        logger.debug("getPowerData from {} to {}", from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                to.format(DateTimeFormatter.ISO_LOCAL_DATE));
        EnedisHttpApi api = this.enedisApi;
        if (api != null) {
            try {
                Consumption consumption = api.getPowerData(userId, prmId, from, to);
                updateStatus(ThingStatus.ONLINE);
                return consumption;
            } catch (LinkyException e) {
                logger.debug("Exception when getting power data: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            }
        }
        return null;
    }

    private boolean isConnected() {
        EnedisHttpApi api = this.enedisApi;
        return api == null ? false : api.isConnected();
    }

    private void disconnect() {
        EnedisHttpApi api = this.enedisApi;
        if (api != null) {
            try {
                api.dispose();
            } catch (LinkyException e) {
                logger.debug("disconnect: {}", e.getMessage());
            }
        }
    }

    @Override
    public void dispose() {
        logger.debug("Disposing the Linky handler.");
        ScheduledFuture<?> job = this.refreshJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(true);
            refreshJob = null;
        }
        disconnect();
        enedisApi = null;
    }

    @Override
    public synchronized void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            logger.debug("Refreshing channel {}", channelUID.getId());
            boolean connectedBefore = isConnected();

            try {
                updateMetaData();
                switch (channelUID.getId()) {
                    case YESTERDAY:
                    case LAST_WEEK:
                    case THIS_WEEK:
                        updateDailyWeeklyData();
                        break;
                    case LAST_MONTH:
                    case THIS_MONTH:
                        updateMonthlyData();
                        break;
                    case LAST_YEAR:
                    case THIS_YEAR:
                        updateYearlyData();
                        break;
                    case PEAK_POWER:
                    case PEAK_TIMESTAMP:
                        updatePowerData();
                        break;
                    default:
                        break;
                }
            } catch (LinkyException ex) {
                logger.debug("Unable to handleCommand refresh", ex);
            }
            if (!connectedBefore && isConnected()) {
                disconnect();
            }
        } else {
            logger.debug("The Linky binding is read-only and can not handle command {}", command);
        }
    }

    private @Nullable Consumption getConsumptionAfterChecks(Consumption consumption, Target target) {
        try {
            checkData(consumption);
        } catch (LinkyException e) {
            logger.debug("Consumption data: {}", e.getMessage());
            return null;
        }
        if (target == Target.FIRST && !isDataFirstDayAvailable(consumption)) {
            logger.debug("Data including yesterday are not yet available");
            return null;
        }
        if (target == Target.LAST && !isDataLastDayAvailable(consumption)) {
            logger.debug("Data including yesterday are not yet available");
            return null;
        }
        return consumption;
    }

    private void checkData(Consumption consumption) throws LinkyException {
        if (consumption.aggregats.days != null && consumption.aggregats.days.datas.isEmpty()) {
            throw new LinkyException("Invalid consumptions data: no day period");
        }
        if (consumption.aggregats.weeks != null && consumption.aggregats.weeks.datas.isEmpty()) {
            throw new LinkyException("Invalid consumptions data: no week period");
        }
        if (consumption.aggregats.months != null && consumption.aggregats.months.datas.isEmpty()) {
            throw new LinkyException("Invalid consumptions data: no month period");
        }
        if (consumption.aggregats.years != null && consumption.aggregats.years.datas.isEmpty()) {
            throw new LinkyException("Invalid consumptions data: no year period");
        }
    }

    private boolean isDataFirstDayAvailable(Consumption consumption) {
        Aggregate days = consumption.aggregats.days;
        logData(days, "First day", false, DateTimeFormatter.ISO_LOCAL_DATE, Target.FIRST);
        return days.datas != null && !days.datas.isEmpty() && !days.datas.get(0).valeur.isNaN();
    }

    private boolean isDataLastDayAvailable(Consumption consumption) {
        Aggregate days = consumption.aggregats.days;
        logData(days, "Last day", false, DateTimeFormatter.ISO_LOCAL_DATE, Target.LAST);
        return days.datas != null && !days.datas.isEmpty() && !days.datas.get(days.datas.size() - 1).valeur.isNaN();
    }

    private void logData(Aggregate aggregate, String title, boolean withDateFin, DateTimeFormatter dateTimeFormatter,
            Target target) {
        if (logger.isDebugEnabled()) {
            int size = (aggregate.datas == null) ? 0 : aggregate.datas.size();
            if (target == Target.FIRST) {
                if (size > 0) {
                    logData(aggregate, 0, title, withDateFin, dateTimeFormatter);
                }
            } else if (target == Target.LAST) {
                if (size > 0) {
                    logData(aggregate, size - 1, title, withDateFin, dateTimeFormatter);
                }
            } else {
                for (int i = 0; i < size; i++) {
                    logData(aggregate, i, title, withDateFin, dateTimeFormatter);
                }
            }
        }
    }

    private void logData(Aggregate aggregate, int index, String title, boolean withDateFin,
            DateTimeFormatter dateTimeFormatter) {
        if (withDateFin) {
            logger.debug("{} {} {} value {}", title, aggregate.datas.get(index).dateDebut.format(dateTimeFormatter),
                    aggregate.datas.get(index).dateFin.format(dateTimeFormatter), aggregate.datas.get(index).valeur);
        } else {
            logger.debug("{} {} value {}", title, aggregate.datas.get(index).dateDebut.format(dateTimeFormatter),
                    aggregate.datas.get(index).valeur);
        }
    }
}
