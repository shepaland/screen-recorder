import { useState, useCallback, Fragment } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ChevronRightIcon,
  ChevronDownIcon,
} from '@heroicons/react/24/outline';
import type {
  TimelineResponse,
  TimelineUser,
} from '../../types/timeline';

interface TimelineTableProps {
  data: TimelineResponse;
}

const HOURS = Array.from({ length: 24 }, (_, i) => i);

function formatDuration(ms: number): string {
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`;
  const minutes = Math.round(ms / 60_000);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remainMins = minutes % 60;
  return remainMins > 0 ? `${hours}h ${remainMins}m` : `${hours}h`;
}

function getTotalActive(user: TimelineUser): number {
  return user.hours.reduce((sum, h) => sum + h.total_duration_ms, 0);
}

// ---- Hour cells ----

function HourCells({
  hours,
  hasRecording,
  onRecordingClick,
  colorClass,
  barColor,
  barHeight,
}: {
  hours: { hour: number; duration_ms: number; has_recording: boolean; session_ids?: string[] }[];
  hasRecording?: boolean;
  onRecordingClick?: (hour: number, sessionIds: string[]) => void;
  colorClass?: string;
  barColor?: string;
  barHeight?: string;
}) {
  const hourMap = new Map(hours.map((h) => [h.hour, h]));
  const cellHeight = barHeight ?? 'h-6';

  return (
    <>
      {HOURS.map((hour) => {
        const h = hourMap.get(hour);
        const duration = h?.duration_ms ?? 0;
        const recording = hasRecording !== undefined ? hasRecording && (h?.has_recording ?? false) : (h?.has_recording ?? false);
        const fillPct = duration > 0 ? Math.min(100, (duration / 3_600_000) * 100) : 0;

        if (duration <= 0) {
          return (
            <td key={hour} className="px-0 py-1">
              <div className={`${cellHeight} w-full rounded-sm bg-gray-50`} />
            </td>
          );
        }

        // Recording → red; barColor (inline) → use style; colorClass → use class; fallback → gray
        const isRecording = recording;
        const bgClass = isRecording
          ? 'bg-red-500 hover:bg-red-600 cursor-pointer'
          : barColor ? '' : (colorClass ?? 'bg-gray-300');
        const bgStyle = isRecording
          ? undefined
          : barColor ? { backgroundColor: barColor, opacity: 0.75 } : undefined;

        return (
          <td key={hour} className="px-0 py-1">
            <div
              className={`${cellHeight} w-full rounded-sm bg-gray-50 relative overflow-hidden group`}
              title={`${String(hour).padStart(2, '0')}:00 -- ${formatDuration(duration)}${recording ? ' (есть запись)' : ''}`}
              onClick={
                recording && onRecordingClick && h?.session_ids
                  ? () => onRecordingClick(hour, h.session_ids ?? [])
                  : undefined
              }
            >
              <div
                className={`absolute left-0 top-0 h-full rounded-sm transition-all ${bgClass}`}
                style={{ width: `${fillPct}%`, ...bgStyle }}
              />
            </div>
          </td>
        );
      })}
    </>
  );
}

// ---- Main component ----

export default function TimelineTable({ data }: TimelineTableProps) {
  const navigate = useNavigate();
  const [expandedUsers, setExpandedUsers] = useState<Set<string>>(new Set());
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [expandedSiteGroups, setExpandedSiteGroups] = useState<Set<string>>(new Set());

  const toggleUser = useCallback((username: string) => {
    setExpandedUsers((prev) => {
      const next = new Set(prev);
      if (next.has(username)) {
        next.delete(username);
        // Also collapse any children
        setExpandedGroups((g) => {
          const ng = new Set(g);
          for (const key of ng) {
            if (key.startsWith(username + '::')) ng.delete(key);
          }
          return ng;
        });
      } else {
        next.add(username);
      }
      return next;
    });
  }, []);

  const toggleGroup = useCallback((key: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
        // Also collapse site groups under this group
        setExpandedSiteGroups((sg) => {
          const nsg = new Set(sg);
          for (const k of nsg) {
            if (k.startsWith(key + '::')) nsg.delete(k);
          }
          return nsg;
        });
      } else {
        next.add(key);
      }
      return next;
    });
  }, []);

  const toggleSiteGroup = useCallback((key: string) => {
    setExpandedSiteGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const handleRecordingClick = useCallback(
    (user: TimelineUser, _hour: number, sessionIds: string[]) => {
      if (user.device_ids.length > 0 && sessionIds.length > 0) {
        const deviceId = user.device_ids[0];
        const sessionId = sessionIds[0];
        navigate(`/archive/devices/${deviceId}?session=${sessionId}&date=${data.date}`);
      }
    },
    [navigate, data.date],
  );

  // Build aggregated app-group hour data
  const getAppGroupHourData = (user: TimelineUser, groupId: string | null, groupName: string) => {
    return HOURS.map((hour) => {
      const hourData = user.hours.find((h) => h.hour === hour);
      if (!hourData) return { hour, duration_ms: 0, has_recording: false, session_ids: [] as string[] };
      const group = hourData.app_groups.find(
        (g) => g.group_id === groupId && g.group_name === groupName,
      );
      return {
        hour,
        duration_ms: group?.duration_ms ?? 0,
        has_recording: hourData.has_recording,
        session_ids: hourData.recording_session_ids,
      };
    });
  };

  // Build per-hour data for an individual app within a group
  const getAppHourData = (user: TimelineUser, groupId: string | null, groupName: string, processName: string) => {
    return HOURS.map((hour) => {
      const hourData = user.hours.find((h) => h.hour === hour);
      if (!hourData) return { hour, duration_ms: 0, has_recording: false, session_ids: [] as string[] };
      const group = hourData.app_groups.find(
        (g) => g.group_id === groupId && g.group_name === groupName,
      );
      if (!group || !group.apps) return { hour, duration_ms: 0, has_recording: false, session_ids: [] as string[] };
      const app = group.apps.find((a) => a.process_name === processName);
      return {
        hour,
        duration_ms: app?.duration_ms ?? 0,
        has_recording: app?.has_recording ?? false,
        session_ids: hourData.recording_session_ids,
      };
    });
  };

  // Build per-hour data for a site group within the browser group
  const getSiteGroupHourData = (user: TimelineUser, browserGroupId: string | null, browserGroupName: string, sgGroupId: string | null, sgGroupName: string) => {
    return HOURS.map((hour) => {
      const hourData = user.hours.find((h) => h.hour === hour);
      if (!hourData) return { hour, duration_ms: 0, has_recording: false, session_ids: [] as string[] };
      const browserGroup = hourData.app_groups.find(
        (g) => g.group_id === browserGroupId && g.group_name === browserGroupName,
      );
      if (!browserGroup || !browserGroup.site_groups) return { hour, duration_ms: 0, has_recording: false, session_ids: [] as string[] };
      const sg = browserGroup.site_groups.find(
        (s) => s.group_id === sgGroupId && s.group_name === sgGroupName,
      );
      return {
        hour,
        duration_ms: sg?.duration_ms ?? 0,
        has_recording: false,
        session_ids: [] as string[],
      };
    });
  };

  // Build per-hour data for an individual site within a site group
  const getSiteHourData = (user: TimelineUser, browserGroupId: string | null, browserGroupName: string, sgGroupId: string | null, sgGroupName: string, domain: string) => {
    return HOURS.map((hour) => {
      const hourData = user.hours.find((h) => h.hour === hour);
      if (!hourData) return { hour, duration_ms: 0, has_recording: false, session_ids: [] as string[] };
      const browserGroup = hourData.app_groups.find(
        (g) => g.group_id === browserGroupId && g.group_name === browserGroupName,
      );
      if (!browserGroup || !browserGroup.site_groups) return { hour, duration_ms: 0, has_recording: false, session_ids: [] as string[] };
      const sg = browserGroup.site_groups.find(
        (s) => s.group_id === sgGroupId && s.group_name === sgGroupName,
      );
      if (!sg) return { hour, duration_ms: 0, has_recording: false, session_ids: [] as string[] };
      const site = sg.sites.find((s) => s.domain === domain);
      return {
        hour,
        duration_ms: site?.duration_ms ?? 0,
        has_recording: site?.has_recording ?? false,
        session_ids: [] as string[],
      };
    });
  };

  return (
    <div className="overflow-x-auto bg-white rounded-lg border border-gray-200 shadow-sm">
      <table className="w-full min-w-[900px] table-fixed">
        <colgroup>
          <col className="w-[220px]" />
          <col className="w-[70px]" />
          {HOURS.map((h) => (
            <col key={h} style={{ width: `${(100 - 20) / 24}%` }} />
          ))}
        </colgroup>
        <thead>
          <tr className="border-b border-gray-200 bg-gray-50">
            <th className="text-left text-xs font-medium text-gray-500 uppercase tracking-wider px-3 py-2">
              Пользователь
            </th>
            <th className="text-right text-xs font-medium text-gray-500 uppercase tracking-wider px-2 py-2">
              Всего
            </th>
            {HOURS.map((h) => (
              <th
                key={h}
                className="text-center text-xs font-medium text-gray-400 px-0 py-2"
              >
                {String(h).padStart(2, '0')}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {data.users.map((user) => {
            const isUserExpanded = expandedUsers.has(user.username);
            const totalActive = getTotalActive(user);
            const userHours = user.hours.map((h) => ({
              hour: h.hour,
              duration_ms: h.total_duration_ms,
              has_recording: h.has_recording,
              session_ids: h.recording_session_ids,
            }));

            // Collect unique app groups across all hours
            const allGroupsMap = new Map<string, { group_id: string | null; group_name: string; color: string; is_browser_group: boolean; total_ms: number }>();
            for (const h of user.hours) {
              for (const ag of h.app_groups) {
                const key = `${ag.group_id ?? 'null'}::${ag.group_name}`;
                const existing = allGroupsMap.get(key);
                if (existing) {
                  existing.total_ms += ag.duration_ms;
                } else {
                  allGroupsMap.set(key, {
                    group_id: ag.group_id,
                    group_name: ag.group_name,
                    color: ag.color,
                    is_browser_group: ag.is_browser_group ?? false,
                    total_ms: ag.duration_ms,
                  });
                }
              }
            }
            const allGroups = Array.from(allGroupsMap.values()).sort((a, b) => b.total_ms - a.total_ms);

            return (
              <Fragment key={user.username}>
                {/* Level 1: User row */}
                <tr
                  className="hover:bg-gray-50 cursor-pointer transition-colors"
                  onClick={() => toggleUser(user.username)}
                >
                  <td className="px-3 py-2">
                    <div className="flex items-center gap-1.5">
                      {isUserExpanded ? (
                        <ChevronDownIcon className="h-4 w-4 text-gray-400 shrink-0" />
                      ) : (
                        <ChevronRightIcon className="h-4 w-4 text-gray-400 shrink-0" />
                      )}
                      <div className="min-w-0">
                        <p className="text-sm font-semibold text-gray-900 truncate" title={user.username}>
                          {user.display_name || user.username}
                        </p>
                        {user.display_name && (
                          <p className="text-xs text-gray-400 truncate">{user.username}</p>
                        )}
                      </div>
                    </div>
                  </td>
                  <td className="text-right px-2 py-2">
                    <span className="text-xs font-medium text-gray-600">{formatDuration(totalActive)}</span>
                  </td>
                  <HourCells
                    hours={userHours}
                    onRecordingClick={(hour, sessionIds) => handleRecordingClick(user, hour, sessionIds)}
                  />
                </tr>

                {/* Level 2: App Groups (expanded) */}
                {isUserExpanded &&
                  allGroups.map((group) => {
                    const groupKey = `${user.username}::${group.group_id ?? 'null'}::${group.group_name}`;
                    const isGroupExpanded = expandedGroups.has(groupKey);
                    const groupHours = getAppGroupHourData(user, group.group_id, group.group_name);

                    // Collect apps/siteGroups across all hours for this group
                    const appsMap = new Map<string, { process_name: string; total_ms: number; has_recording: boolean }>();
                    const siteGroupsMap = new Map<string, { group_id: string | null; group_name: string; color: string; total_ms: number; sites: Map<string, { domain: string; total_ms: number; has_recording: boolean }> }>();

                    for (const h of user.hours) {
                      const ag = h.app_groups.find(
                        (g) => g.group_id === group.group_id && g.group_name === group.group_name,
                      );
                      if (!ag) continue;

                      // Apps
                      if (ag.apps) {
                        for (const app of ag.apps) {
                          const existing = appsMap.get(app.process_name);
                          if (existing) {
                            existing.total_ms += app.duration_ms;
                            if (app.has_recording) existing.has_recording = true;
                          } else {
                            appsMap.set(app.process_name, {
                              process_name: app.process_name,
                              total_ms: app.duration_ms,
                              has_recording: app.has_recording,
                            });
                          }
                        }
                      }

                      // Site groups (for browser groups)
                      if (ag.site_groups) {
                        for (const sg of ag.site_groups) {
                          const sgKey = `${sg.group_id ?? 'null'}::${sg.group_name}`;
                          const existingSg = siteGroupsMap.get(sgKey);
                          if (existingSg) {
                            existingSg.total_ms += sg.duration_ms;
                            for (const site of sg.sites) {
                              const existingSite = existingSg.sites.get(site.domain);
                              if (existingSite) {
                                existingSite.total_ms += site.duration_ms;
                                if (site.has_recording) existingSite.has_recording = true;
                              } else {
                                existingSg.sites.set(site.domain, { ...site, total_ms: site.duration_ms });
                              }
                            }
                          } else {
                            const sitesMap = new Map<string, { domain: string; total_ms: number; has_recording: boolean }>();
                            for (const site of sg.sites) {
                              sitesMap.set(site.domain, { ...site, total_ms: site.duration_ms });
                            }
                            siteGroupsMap.set(sgKey, {
                              group_id: sg.group_id,
                              group_name: sg.group_name,
                              color: sg.color,
                              total_ms: sg.duration_ms,
                              sites: sitesMap,
                            });
                          }
                        }
                      }
                    }

                    const apps = Array.from(appsMap.values()).sort((a, b) => b.total_ms - a.total_ms);
                    const siteGroups = Array.from(siteGroupsMap.values()).sort((a, b) => b.total_ms - a.total_ms);
                    const hasChildren = apps.length > 0 || siteGroups.length > 0;

                    return (
                      <Fragment key={groupKey}>
                        {/* App group row — with colored hour bars */}
                        <tr
                          className={`hover:bg-gray-50 transition-colors ${hasChildren ? 'cursor-pointer' : ''}`}
                          onClick={hasChildren ? () => toggleGroup(groupKey) : undefined}
                        >
                          <td className="pl-8 pr-3 py-1.5">
                            <div className="flex items-center gap-1.5">
                              {hasChildren ? (
                                isGroupExpanded ? (
                                  <ChevronDownIcon className="h-3.5 w-3.5 text-gray-400 shrink-0" />
                                ) : (
                                  <ChevronRightIcon className="h-3.5 w-3.5 text-gray-400 shrink-0" />
                                )
                              ) : (
                                <span className="w-3.5" />
                              )}
                              <span
                                className="w-2.5 h-2.5 rounded-full shrink-0"
                                style={{ backgroundColor: group.color || '#9CA3AF' }}
                              />
                              <span className="text-sm text-gray-700 truncate">{group.group_name}</span>
                            </div>
                          </td>
                          <td className="text-right px-2 py-1.5">
                            <span className="text-xs text-gray-500">{formatDuration(group.total_ms)}</span>
                          </td>
                          <HourCells
                            hours={groupHours}
                            barColor={group.color || '#9CA3AF'}
                            barHeight="h-5"
                          />
                        </tr>

                        {/* Level 3: Apps (non-browser groups) — with per-app hour bars */}
                        {isGroupExpanded && !group.is_browser_group &&
                          apps.map((app) => {
                            const appHours = getAppHourData(user, group.group_id, group.group_name, app.process_name);
                            return (
                              <tr key={`${groupKey}::app::${app.process_name}`} className="hover:bg-gray-50">
                                <td className="pl-14 pr-3 py-1">
                                  <span className="text-xs text-gray-600 truncate block">{app.process_name}</span>
                                </td>
                                <td className="text-right px-2 py-1">
                                  <span className="text-xs text-gray-400">{formatDuration(app.total_ms)}</span>
                                </td>
                                <HourCells
                                  hours={appHours}
                                  barColor={group.color || '#9CA3AF'}
                                  barHeight="h-4"
                                />
                              </tr>
                            );
                          })}

                        {/* Level 3: Site Groups (browser groups) — with per-site-group hour bars */}
                        {isGroupExpanded && group.is_browser_group &&
                          siteGroups.map((sg) => {
                            const sgKey = `${groupKey}::sg::${sg.group_id ?? 'null'}::${sg.group_name}`;
                            const isSgExpanded = expandedSiteGroups.has(sgKey);
                            const hasSites = sg.sites.size > 0;
                            const sgHours = getSiteGroupHourData(user, group.group_id, group.group_name, sg.group_id, sg.group_name);

                            return (
                              <Fragment key={sgKey}>
                                <tr
                                  className={`hover:bg-gray-50 transition-colors ${hasSites ? 'cursor-pointer' : ''}`}
                                  onClick={hasSites ? () => toggleSiteGroup(sgKey) : undefined}
                                >
                                  <td className="pl-14 pr-3 py-1">
                                    <div className="flex items-center gap-1.5">
                                      {hasSites ? (
                                        isSgExpanded ? (
                                          <ChevronDownIcon className="h-3 w-3 text-gray-400 shrink-0" />
                                        ) : (
                                          <ChevronRightIcon className="h-3 w-3 text-gray-400 shrink-0" />
                                        )
                                      ) : (
                                        <span className="w-3" />
                                      )}
                                      <span
                                        className="w-2 h-2 rounded-full shrink-0"
                                        style={{ backgroundColor: sg.color || '#9CA3AF' }}
                                      />
                                      <span className="text-xs text-gray-600 truncate">{sg.group_name}</span>
                                    </div>
                                  </td>
                                  <td className="text-right px-2 py-1">
                                    <span className="text-xs text-gray-400">{formatDuration(sg.total_ms)}</span>
                                  </td>
                                  <HourCells
                                    hours={sgHours}
                                    barColor={sg.color || '#9CA3AF'}
                                    barHeight="h-4"
                                  />
                                </tr>

                                {/* Level 4: Individual sites — with per-site hour bars */}
                                {isSgExpanded &&
                                  Array.from(sg.sites.values())
                                    .sort((a, b) => b.total_ms - a.total_ms)
                                    .map((site) => {
                                      const siteHours = getSiteHourData(user, group.group_id, group.group_name, sg.group_id, sg.group_name, site.domain);
                                      return (
                                        <tr key={`${sgKey}::${site.domain}`} className="hover:bg-gray-50">
                                          <td className="pl-20 pr-3 py-1">
                                            <span className="text-xs text-gray-500 truncate block">{site.domain}</span>
                                          </td>
                                          <td className="text-right px-2 py-1">
                                            <span className="text-xs text-gray-400">{formatDuration(site.total_ms)}</span>
                                          </td>
                                          <HourCells
                                            hours={siteHours}
                                            barColor={sg.color || '#9CA3AF'}
                                            barHeight="h-3"
                                          />
                                        </tr>
                                      );
                                    })}
                              </Fragment>
                            );
                          })}

                        {/* If browser group is expanded but also has regular apps, show them too */}
                        {isGroupExpanded && group.is_browser_group &&
                          apps.map((app) => {
                            const appHours = getAppHourData(user, group.group_id, group.group_name, app.process_name);
                            return (
                              <tr key={`${groupKey}::app::${app.process_name}`} className="hover:bg-gray-50">
                                <td className="pl-14 pr-3 py-1">
                                  <span className="text-xs text-gray-600 truncate block">{app.process_name}</span>
                                </td>
                                <td className="text-right px-2 py-1">
                                  <span className="text-xs text-gray-400">{formatDuration(app.total_ms)}</span>
                                </td>
                                <HourCells
                                  hours={appHours}
                                  barColor={group.color || '#2196F3'}
                                  barHeight="h-4"
                                />
                              </tr>
                            );
                          })}
                      </Fragment>
                    );
                  })}
              </Fragment>
            );
          })}
        </tbody>
      </table>

      {/* Legend */}
      <div className="flex items-center gap-4 px-4 py-3 border-t border-gray-200 bg-gray-50 text-xs text-gray-500">
        <div className="flex items-center gap-1.5">
          <div className="w-3 h-3 rounded-sm bg-gray-300" />
          <span>Активность</span>
        </div>
        <div className="flex items-center gap-1.5">
          <div className="w-3 h-3 rounded-sm bg-red-500" />
          <span>Есть запись (кликните для просмотра)</span>
        </div>
        <div className="flex items-center gap-1.5">
          <div className="w-3 h-3 rounded-sm bg-gray-50 border border-gray-200" />
          <span>Нет активности</span>
        </div>
      </div>
    </div>
  );
}
