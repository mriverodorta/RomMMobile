const signup = document.getElementById("tester-signup");
const button = signup.querySelector("button");
const statusMessage = document.getElementById("tester-status");
button.disabled = false;

signup.addEventListener("submit", async (event) => {
  event.preventDefault();
  button.disabled = true;
  statusMessage.textContent = "Sending your request…";
  const fields = new FormData(signup);

  try {
    const requestOptions = {
      credentials: "omit",
      redirect: "error",
      signal: AbortSignal.timeout(10000),
    };
    const health = await fetch(new URL("/healthz", signup.action), requestOptions);
    if (!health.ok || (await health.json()).status !== "rommmobiles_testers_ready") {
      throw new Error("Signup unavailable");
    }
    const response = await fetch(signup.action, {
      ...requestOptions,
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: fields.get("email"), website: fields.get("website") }),
    });
    if (response.status === 429) {
      statusMessage.textContent = "Signups are busy. Please try again later or request an invite by email.";
      return;
    }
    const result = await response.json();
    if (response.status === 400 && result.status === "email") {
      statusMessage.textContent = "Enter the email address you use for Google Play.";
      return;
    }
    if (!response.ok || result.status !== "received") throw new Error("Signup unavailable");
    signup.reset();
    statusMessage.textContent = "Request received. If you’re invited, we’ll email you a Google Play opt-in link.";
  } catch {
    statusMessage.textContent = "Signup is temporarily unavailable. You can request an invite by email.";
  } finally {
    button.disabled = false;
  }
});
