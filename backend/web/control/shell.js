(function () {
  function closeNav() {
    document.body.classList.remove("nav-open");
    var btn = document.getElementById("menuBtn");
    var backdrop = document.getElementById("navBackdrop");
    if (btn) btn.setAttribute("aria-expanded", "false");
    if (backdrop) backdrop.hidden = true;
  }

  function openNav() {
    document.body.classList.add("nav-open");
    var btn = document.getElementById("menuBtn");
    var backdrop = document.getElementById("navBackdrop");
    if (btn) btn.setAttribute("aria-expanded", "true");
    if (backdrop) backdrop.hidden = false;
  }

  var menuBtn = document.getElementById("menuBtn");
  if (menuBtn) {
    menuBtn.addEventListener("click", function () {
      if (document.body.classList.contains("nav-open")) closeNav();
      else openNav();
    });
  }

  var backdrop = document.getElementById("navBackdrop");
  if (backdrop) {
    backdrop.addEventListener("click", closeNav);
  }

  var nav = document.getElementById("sidebarNav");
  if (nav) {
    nav.addEventListener("click", function (e) {
      var a = e.target.closest("a");
      if (a) closeNav();
    });
  }

  var logoutBtn = document.getElementById("logoutBtn");
  if (logoutBtn) {
    logoutBtn.addEventListener("click", function () {
      fetch("/admin/api/logout", {
        method: "POST",
        credentials: "same-origin",
        headers: { Accept: "application/json" },
      }).finally(function () {
        window.location.href = "/admin/login";
      });
    });
  }

  fetch("/admin/api/session", {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  })
    .then(function (r) {
      if (!r.ok) return null;
      return r.json();
    })
    .then(function (data) {
      if (!data || !data.authenticated) return;
      var el = document.getElementById("adminDisplayName");
      if (el && data.username) el.textContent = data.username;
      if (data.environment) {
        var badge = document.getElementById("envBadge");
        if (badge) {
          badge.textContent = data.environment;
          badge.setAttribute("data-env", data.environment);
        }
      }
    })
    .catch(function () {});

  /* ---- Stage 6A-4 Overview (pathname /admin only; no polling) ---- */
  function isOverviewPath() {
    var path = (window.location.pathname || "").replace(/\/+$/, "") || "/";
    return path === "/admin";
  }

  function titleCaseStatus(value) {
    if (!value || typeof value !== "string") return "—";
    return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
  }

  function toneFor(kind, status) {
    var s = (status || "").toLowerCase();
    if (kind === "hermes") {
      if (s === "active") return "healthy";
      if (s === "warning") return "warning";
      return "critical";
    }
    if (s === "online" || s === "connected") return "healthy";
    return "critical";
  }

  function setTone(cardEl, badgeEl, tone, label) {
    if (cardEl) cardEl.setAttribute("data-tone", tone);
    if (badgeEl) {
      badgeEl.setAttribute("data-tone", tone);
      badgeEl.textContent = label;
    }
  }

  function formatActivityTime(iso) {
    if (!iso || typeof iso !== "string") return "";
    var then = Date.parse(iso);
    if (isNaN(then)) return "";
    var sec = Math.round((Date.now() - then) / 1000);
    if (sec < 60) return "Just now";
    if (sec < 3600) {
      var m = Math.floor(sec / 60);
      return m === 1 ? "1 minute ago" : m + " minutes ago";
    }
    if (sec < 86400) {
      var h = Math.floor(sec / 3600);
      return h === 1 ? "1 hour ago" : h + " hours ago";
    }
    try {
      return new Date(then).toLocaleString(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      });
    } catch (e) {
      return "";
    }
  }

  function activityGlyph(type) {
    var map = {
      device_register: "U",
      device_connected: "U",
      user_connected: "U",
      candidates_submit: "M",
      memory_create: "M",
      memory_consolidate: "M",
      memory_approve: "✓",
      knowledge_ingest: "D",
      video_upload: "V",
      video_uploaded: "V",
      action_flow_triggered: "→",
      action_flow_failed: "!",
      hermes_warning: "H",
    };
    return map[type] || "•";
  }

  function setLoadingButtons(loading) {
    var refresh = document.getElementById("overviewRefreshBtn");
    var retry = document.getElementById("overviewRetryBtn");
    if (refresh) refresh.disabled = !!loading;
    if (retry) retry.disabled = !!loading;
  }

  function showOverviewError() {
    var err = document.getElementById("overviewError");
    var status = document.getElementById("overviewStatus");
    if (status) status.hidden = true;
    if (err) err.hidden = false;
  }

  function hideOverviewError() {
    var err = document.getElementById("overviewError");
    if (err) err.hidden = true;
  }

  function renderOverview(data) {
    hideOverviewError();
    var grid = document.getElementById("overviewGrid");
    if (grid) grid.setAttribute("aria-busy", "false");

    var backendStatus = (data.backend && data.backend.status) || "offline";
    var dbStatus = (data.database && data.database.status) || "unavailable";
    var hermes = data.hermes || {};
    var hermesStatus = hermes.status || "offline";
    var users = (data.connected_users && data.connected_users.count) || 0;
    var proposals = (data.memory_proposals && data.memory_proposals.count) || 0;
    var flows = data.active_action_flows || {};
    var flowCount = typeof flows.count === "number" ? flows.count : 0;
    var configured = !!flows.configured;

    var valBackend = document.getElementById("valBackend");
    var badgeBackend = document.getElementById("badgeBackend");
    var cardBackend = document.getElementById("cardBackend");
    if (valBackend) valBackend.textContent = titleCaseStatus(backendStatus);
    setTone(cardBackend, badgeBackend, toneFor("backend", backendStatus), titleCaseStatus(backendStatus));

    var valDatabase = document.getElementById("valDatabase");
    var badgeDatabase = document.getElementById("badgeDatabase");
    var cardDatabase = document.getElementById("cardDatabase");
    if (valDatabase) valDatabase.textContent = titleCaseStatus(dbStatus);
    setTone(cardDatabase, badgeDatabase, toneFor("database", dbStatus), titleCaseStatus(dbStatus));

    var valHermes = document.getElementById("valHermes");
    var badgeHermes = document.getElementById("badgeHermes");
    var cardHermes = document.getElementById("cardHermes");
    if (valHermes) valHermes.textContent = titleCaseStatus(hermesStatus);
    setTone(cardHermes, badgeHermes, toneFor("hermes", hermesStatus), titleCaseStatus(hermesStatus));
    var metaVer = document.getElementById("metaHermesVersion");
    if (metaVer) {
      metaVer.textContent = hermes.version ? "Version: " + hermes.version : "";
    }
    var metaAct = document.getElementById("metaHermesActivity");
    if (metaAct) {
      if (hermes.last_activity) {
        var rel = formatActivityTime(hermes.last_activity);
        metaAct.textContent = rel ? "Last activity: " + rel : "";
      } else {
        metaAct.textContent = "";
      }
    }

    var valUsers = document.getElementById("valUsers");
    if (valUsers) valUsers.textContent = String(users);

    var valProposals = document.getElementById("valProposals");
    if (valProposals) valProposals.textContent = String(proposals);
    var actions = document.getElementById("proposalsActions");
    if (actions) actions.hidden = !(proposals > 0);

    var valFlows = document.getElementById("valFlows");
    var metaFlows = document.getElementById("metaFlows");
    if (valFlows) valFlows.textContent = String(flowCount);
    if (metaFlows) {
      metaFlows.textContent = configured ? "Active flows" : "Not configured";
    }

    var list = document.getElementById("recentActivityList");
    var empty = document.getElementById("recentActivityEmpty");
    var items = Array.isArray(data.recent_activity) ? data.recent_activity : [];
    if (list) {
      list.innerHTML = "";
      items.forEach(function (item) {
        if (!item || typeof item.label !== "string") return;
        var li = document.createElement("li");
        li.className = "cc-activity-item";
        var badge = document.createElement("span");
        badge.className = "cc-activity-badge";
        badge.setAttribute("aria-hidden", "true");
        badge.textContent = activityGlyph(item.type);
        var body = document.createElement("div");
        body.className = "cc-activity-body";
        var label = document.createElement("p");
        label.className = "cc-activity-label";
        label.textContent = item.label;
        var time = document.createElement("p");
        time.className = "cc-activity-time";
        time.textContent = formatActivityTime(item.timestamp) || "";
        body.appendChild(label);
        if (time.textContent) body.appendChild(time);
        li.appendChild(badge);
        li.appendChild(body);
        list.appendChild(li);
      });
    }
    if (empty) empty.hidden = items.length > 0;

    var status = document.getElementById("overviewStatus");
    if (status) {
      status.hidden = true;
      status.textContent = "";
    }
  }

  var overviewLoading = false;

  function loadOverview() {
    if (overviewLoading) return;
    overviewLoading = true;
    setLoadingButtons(true);
    hideOverviewError();
    var grid = document.getElementById("overviewGrid");
    if (grid) grid.setAttribute("aria-busy", "true");

    fetch("/admin/api/overview", {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    })
      .then(function (r) {
        if (r.status === 401) {
          window.location.href = "/admin/login";
          return null;
        }
        if (!r.ok) {
          showOverviewError();
          return null;
        }
        return r.json();
      })
      .then(function (data) {
        if (!data) return;
        renderOverview(data);
      })
      .catch(function () {
        showOverviewError();
      })
      .finally(function () {
        overviewLoading = false;
        setLoadingButtons(false);
        if (grid) grid.setAttribute("aria-busy", "false");
      });
  }

  function initOverview() {
    if (!isOverviewPath()) return;
    var placeholder = document.getElementById("sectionPlaceholder");
    var root = document.getElementById("overviewRoot");
    if (placeholder) placeholder.hidden = true;
    if (root) root.hidden = false;

    var refresh = document.getElementById("overviewRefreshBtn");
    if (refresh && !refresh.dataset.bound) {
      refresh.dataset.bound = "1";
      refresh.addEventListener("click", function () {
        loadOverview();
      });
    }
    var retry = document.getElementById("overviewRetryBtn");
    if (retry && !retry.dataset.bound) {
      retry.dataset.bound = "1";
      retry.addEventListener("click", function () {
        loadOverview();
      });
    }
    loadOverview();
  }

  initOverview();

  /* ---- Stage 6B-2 Users list + detail (no polling, no writes) ---- */
  var USERS_PAGE_SIZE = 25;
  var usersState = { page: 1, loading: false, total: 0 };
  var userDetailLoading = false;

  function currentPath() {
    return (window.location.pathname || "").replace(/\/+$/, "") || "/";
  }

  function isUsersListPath() {
    return currentPath() === "/admin/users";
  }

  function usersDetailId() {
    var path = currentPath();
    var prefix = "/admin/users/";
    if (!path.startsWith(prefix)) return null;
    var id = path.slice(prefix.length).split("/")[0];
    if (!id || id.indexOf("..") !== -1) return null;
    return decodeURIComponent(id);
  }

  function formatRelativeTime(iso) {
    if (!iso || typeof iso !== "string") return "Never";
    var then = Date.parse(iso);
    if (isNaN(then)) return "Never";
    var sec = Math.round((Date.now() - then) / 1000);
    if (sec < 60) return "Just now";
    if (sec < 3600) {
      var m = Math.floor(sec / 60);
      return m === 1 ? "1 minute ago" : m + " minutes ago";
    }
    if (sec < 86400) {
      var h = Math.floor(sec / 3600);
      return h === 1 ? "1 hour ago" : h + " hours ago";
    }
    var d = Math.floor(sec / 86400);
    if (d < 14) return d === 1 ? "1 day ago" : d + " days ago";
    try {
      return new Date(then).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
    } catch (e) {
      return "Never";
    }
  }

  function truncateId(id) {
    if (!id || typeof id !== "string") return "";
    if (id.length <= 12) return id;
    return id.slice(0, 8) + "…" + id.slice(-4);
  }

  function labelMemory(connected) {
    return connected ? "Connected" : "Not connected";
  }

  function labelHealth(health) {
    var map = {
      healthy: "Healthy",
      offline: "Offline",
      never_connected: "Never connected",
      slow: "Slow",
    };
    return map[health] || "";
  }

  function labelStatus(status) {
    var map = {
      active: "Active",
      needs_attention: "Needs attention",
      offline: "Offline",
    };
    return map[status] || titleCaseStatus(status);
  }

  function statusTone(status) {
    if (status === "active") return "healthy";
    if (status === "needs_attention") return "warning";
    return "critical";
  }

  function setUsersLoadingButtons(loading) {
    ["usersRefreshBtn", "usersRetryBtn", "usersPrevBtn", "usersNextBtn"].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.disabled = !!loading;
    });
  }

  function hideUsersPanels() {
    ["usersError", "usersEmpty", "usersStatus"].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.hidden = true;
    });
  }

  function renderUsersRows(users) {
    var tbody = document.getElementById("usersTableBody");
    if (!tbody) return;
    tbody.innerHTML = "";
    (users || []).forEach(function (u) {
      if (!u || typeof u.device_id !== "string") return;
      var tr = document.createElement("tr");

      function td(label, htmlOrText, isHtml) {
        var cell = document.createElement("td");
        cell.setAttribute("data-label", label);
        if (isHtml) cell.innerHTML = htmlOrText;
        else cell.textContent = htmlOrText;
        return cell;
      }

      var nameCell = document.createElement("td");
      nameCell.setAttribute("data-label", "User / Device");
      var name = document.createElement("p");
      name.className = "cc-user-name";
      name.textContent = u.display_name || "Device";
      var idLine = document.createElement("p");
      idLine.className = "cc-user-id";
      idLine.textContent = truncateId(u.device_id);
      nameCell.appendChild(name);
      nameCell.appendChild(idLine);
      tr.appendChild(nameCell);

      tr.appendChild(td("Last Active", formatRelativeTime(u.last_active)));
      tr.appendChild(td("App Version", u.app_version || "Unknown"));

      var memCell = document.createElement("td");
      memCell.setAttribute("data-label", "Memory");
      var memMain = document.createElement("p");
      memMain.className = "cc-user-name";
      memMain.textContent = labelMemory(!!u.memory_connected);
      var memSub = document.createElement("p");
      memSub.className = "cc-sublabel";
      memSub.textContent = labelHealth(u.memory_health);
      memCell.appendChild(memMain);
      if (memSub.textContent) memCell.appendChild(memSub);
      tr.appendChild(memCell);

      tr.appendChild(td("Personality", u.personality || "Default"));

      var statusCell = document.createElement("td");
      statusCell.setAttribute("data-label", "Status");
      var badge = document.createElement("span");
      badge.className = "cc-stat-badge";
      badge.setAttribute("data-tone", statusTone(u.status));
      badge.textContent = labelStatus(u.status);
      statusCell.appendChild(badge);
      tr.appendChild(statusCell);

      var openCell = document.createElement("td");
      openCell.setAttribute("data-label", "Open");
      var openLink = document.createElement("a");
      openLink.className = "cc-btn cc-btn-link";
      openLink.href = "/admin/users/" + encodeURIComponent(u.device_id);
      openLink.textContent = "Open";
      openCell.appendChild(openLink);
      tr.appendChild(openCell);

      tbody.appendChild(tr);
    });
  }

  function updateUsersPagination(data) {
    var page = data.page || 1;
    var pageSize = data.page_size || USERS_PAGE_SIZE;
    var total = typeof data.total === "number" ? data.total : 0;
    var maxPage = Math.max(1, Math.ceil(total / pageSize) || 1);
    var prev = document.getElementById("usersPrevBtn");
    var next = document.getElementById("usersNextBtn");
    var meta = document.getElementById("usersPageMeta");
    var totalMeta = document.getElementById("usersTotalMeta");
    if (meta) meta.textContent = "Page " + page;
    if (totalMeta) totalMeta.textContent = total + (total === 1 ? " user" : " users");
    if (prev) prev.disabled = page <= 1 || usersState.loading;
    if (next) next.disabled = page >= maxPage || usersState.loading;
  }

  function usersQuery() {
    var params = new URLSearchParams();
    params.set("page", String(usersState.page || 1));
    params.set("page_size", String(USERS_PAGE_SIZE));
    var search = document.getElementById("usersSearch");
    var memory = document.getElementById("usersMemoryFilter");
    var status = document.getElementById("usersStatusFilter");
    if (search && search.value.trim()) params.set("search", search.value.trim());
    if (memory && memory.value) params.set("memory", memory.value);
    if (status && status.value) params.set("status", status.value);
    return params.toString();
  }

  function loadUsers() {
    if (usersState.loading) return;
    usersState.loading = true;
    setUsersLoadingButtons(true);
    hideUsersPanels();
    var card = document.getElementById("usersTableCard");
    var loading = document.getElementById("usersLoading");
    var table = document.getElementById("usersTable");
    if (card) {
      card.hidden = false;
      card.setAttribute("aria-busy", "true");
    }
    if (loading) loading.hidden = false;
    if (table) table.hidden = true;

    fetch("/admin/api/users?" + usersQuery(), {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    })
      .then(function (r) {
        if (r.status === 401) {
          window.location.href = "/admin/login";
          return null;
        }
        if (!r.ok) {
          var err = document.getElementById("usersError");
          if (err) err.hidden = false;
          if (card) card.hidden = true;
          return null;
        }
        return r.json();
      })
      .then(function (data) {
        if (!data) return;
        var users = Array.isArray(data.users) ? data.users : [];
        var total = typeof data.total === "number" ? data.total : users.length;
        usersState.total = total;
        usersState.page = data.page || usersState.page;
        if (total === 0) {
          var empty = document.getElementById("usersEmpty");
          if (empty) empty.hidden = false;
          if (card) card.hidden = true;
          updateUsersPagination({ page: 1, page_size: USERS_PAGE_SIZE, total: 0 });
          return;
        }
        if (card) card.hidden = false;
        if (table) table.hidden = false;
        renderUsersRows(users);
        updateUsersPagination(data);
      })
      .catch(function () {
        var err = document.getElementById("usersError");
        if (err) err.hidden = false;
        if (card) card.hidden = true;
      })
      .finally(function () {
        usersState.loading = false;
        setUsersLoadingButtons(false);
        if (loading) loading.hidden = true;
        if (card) card.setAttribute("aria-busy", "false");
        updateUsersPagination({
          page: usersState.page,
          page_size: USERS_PAGE_SIZE,
          total: usersState.total,
        });
      });
  }

  function bindUsersListControls() {
    function resetAndLoad() {
      usersState.page = 1;
      loadUsers();
    }
    var search = document.getElementById("usersSearch");
    if (search && !search.dataset.bound) {
      search.dataset.bound = "1";
      search.addEventListener("keydown", function (e) {
        if (e.key === "Enter") {
          e.preventDefault();
          resetAndLoad();
        }
      });
      search.addEventListener("change", resetAndLoad);
    }
    ["usersMemoryFilter", "usersStatusFilter"].forEach(function (id) {
      var el = document.getElementById(id);
      if (el && !el.dataset.bound) {
        el.dataset.bound = "1";
        el.addEventListener("change", resetAndLoad);
      }
    });
    var refresh = document.getElementById("usersRefreshBtn");
    if (refresh && !refresh.dataset.bound) {
      refresh.dataset.bound = "1";
      refresh.addEventListener("click", function () {
        loadUsers();
      });
    }
    var retry = document.getElementById("usersRetryBtn");
    if (retry && !retry.dataset.bound) {
      retry.dataset.bound = "1";
      retry.addEventListener("click", function () {
        loadUsers();
      });
    }
    var prev = document.getElementById("usersPrevBtn");
    if (prev && !prev.dataset.bound) {
      prev.dataset.bound = "1";
      prev.addEventListener("click", function () {
        if (usersState.page > 1) {
          usersState.page -= 1;
          loadUsers();
        }
      });
    }
    var next = document.getElementById("usersNextBtn");
    if (next && !next.dataset.bound) {
      next.dataset.bound = "1";
      next.addEventListener("click", function () {
        usersState.page += 1;
        loadUsers();
      });
    }
  }

  function initUsersList() {
    if (!isUsersListPath()) return;
    var placeholder = document.getElementById("sectionPlaceholder");
    var root = document.getElementById("usersRoot");
    var detail = document.getElementById("userDetailRoot");
    var overview = document.getElementById("overviewRoot");
    if (placeholder) placeholder.hidden = true;
    if (overview) overview.hidden = true;
    if (detail) detail.hidden = true;
    if (root) root.hidden = false;
    bindUsersListControls();
    loadUsers();
  }

  function setDetailLoadingButtons(loading) {
    ["userDetailRefreshBtn", "userDetailRetryBtn"].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.disabled = !!loading;
    });
  }

  function renderUserDetail(data) {
    var card = document.getElementById("userDetailCard");
    var title = document.getElementById("userDetailTitle");
    var grid = document.getElementById("userDetailGrid");
    var loading = document.getElementById("userDetailLoading");
    var notFound = document.getElementById("userDetailNotFound");
    var err = document.getElementById("userDetailError");
    if (notFound) notFound.hidden = true;
    if (err) err.hidden = true;
    if (card) {
      card.hidden = false;
      card.setAttribute("aria-busy", "false");
    }
    if (loading) loading.hidden = true;
    if (title) title.textContent = data.display_name || "User";
    if (!grid) return;
    grid.innerHTML = "";
    var fields = [
      ["Device ID", data.device_id || "—"],
      ["First connected", formatRelativeTime(data.first_connected)],
      ["Last active", formatRelativeTime(data.last_active)],
      ["App version", data.app_version || "Unknown"],
      ["Language", data.language || "Unknown"],
      ["Machine model", data.machine_model || "Unknown"],
      ["Memory connected", labelMemory(!!data.memory_connected)],
      ["Memory connection health", labelHealth(data.memory_health) || "—"],
      ["Assigned personality", data.personality || "Default"],
      ["Approved memories", String(data.approved_memory_count != null ? data.approved_memory_count : 0)],
      ["Proposed memories", String(data.proposed_memory_count != null ? data.proposed_memory_count : 0)],
    ];
    fields.forEach(function (pair) {
      var dt = document.createElement("dt");
      dt.textContent = pair[0];
      var dd = document.createElement("dd");
      dd.textContent = pair[1];
      grid.appendChild(dt);
      grid.appendChild(dd);
    });
    var flows = Array.isArray(data.recent_action_flows) ? data.recent_action_flows : [];
    var list = document.getElementById("userDetailFlowsList");
    var empty = document.getElementById("userDetailFlowsEmpty");
    if (list) {
      list.innerHTML = "";
      flows.forEach(function (item) {
        if (!item || typeof item !== "object") return;
        var li = document.createElement("li");
        li.className = "cc-activity-item";
        var label = document.createElement("p");
        label.className = "cc-activity-label";
        label.textContent = item.label || item.type || "Action Flow";
        li.appendChild(label);
        list.appendChild(li);
      });
    }
    if (empty) empty.hidden = flows.length > 0;
  }

  function loadUserDetail(deviceId) {
    if (userDetailLoading) return;
    userDetailLoading = true;
    setDetailLoadingButtons(true);
    var card = document.getElementById("userDetailCard");
    var loading = document.getElementById("userDetailLoading");
    var err = document.getElementById("userDetailError");
    var notFound = document.getElementById("userDetailNotFound");
    var errText = document.getElementById("userDetailErrorText");
    if (err) err.hidden = true;
    if (notFound) notFound.hidden = true;
    if (card) {
      card.hidden = false;
      card.setAttribute("aria-busy", "true");
    }
    if (loading) loading.hidden = false;

    fetch("/admin/api/users/" + encodeURIComponent(deviceId), {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    })
      .then(function (r) {
        if (r.status === 401) {
          window.location.href = "/admin/login";
          return null;
        }
        if (r.status === 404) {
          if (card) card.hidden = true;
          if (notFound) notFound.hidden = false;
          return null;
        }
        if (!r.ok) {
          if (card) card.hidden = true;
          if (errText) errText.textContent = "Unable to load this user right now.";
          if (err) err.hidden = false;
          return null;
        }
        return r.json();
      })
      .then(function (data) {
        if (!data) return;
        renderUserDetail(data);
      })
      .catch(function () {
        if (card) card.hidden = true;
        if (errText) errText.textContent = "Unable to load this user right now.";
        if (err) err.hidden = false;
      })
      .finally(function () {
        userDetailLoading = false;
        setDetailLoadingButtons(false);
        if (loading) loading.hidden = true;
        if (card) card.setAttribute("aria-busy", "false");
      });
  }

  function initUserDetail() {
    var deviceId = usersDetailId();
    if (!deviceId) return;
    var placeholder = document.getElementById("sectionPlaceholder");
    var root = document.getElementById("usersRoot");
    var detail = document.getElementById("userDetailRoot");
    var overview = document.getElementById("overviewRoot");
    if (placeholder) placeholder.hidden = true;
    if (overview) overview.hidden = true;
    if (root) root.hidden = true;
    if (detail) detail.hidden = false;

    var refresh = document.getElementById("userDetailRefreshBtn");
    if (refresh && !refresh.dataset.bound) {
      refresh.dataset.bound = "1";
      refresh.addEventListener("click", function () {
        loadUserDetail(deviceId);
      });
    }
    var retry = document.getElementById("userDetailRetryBtn");
    if (retry && !retry.dataset.bound) {
      retry.dataset.bound = "1";
      retry.addEventListener("click", function () {
        loadUserDetail(deviceId);
      });
    }
    loadUserDetail(deviceId);
  }

  initUsersList();
  initUserDetail();
})();
