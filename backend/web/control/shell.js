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
})();
