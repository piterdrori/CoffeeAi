(function () {
  var form = document.getElementById("loginForm");
  var error = document.getElementById("error");
  if (!form) return;
  form.addEventListener("submit", function (e) {
    e.preventDefault();
    error.hidden = true;
    var password = document.getElementById("password").value;
    fetch("/admin/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({ password: password }),
    })
      .then(function (r) {
        if (r.status === 429) {
          error.textContent = "Too many attempts. Try again later.";
          error.hidden = false;
          return null;
        }
        if (!r.ok) {
          error.textContent = "Invalid credentials";
          error.hidden = false;
          return null;
        }
        window.location.href = "/admin";
        return null;
      })
      .catch(function () {
        error.textContent = "Invalid credentials";
        error.hidden = false;
      });
  });
})();
