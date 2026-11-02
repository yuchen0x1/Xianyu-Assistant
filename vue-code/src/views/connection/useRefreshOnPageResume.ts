import { onBeforeUnmount, onMounted } from "vue";

export function useRefreshOnPageResume(refresh: () => void | Promise<void>) {
  let active = true;
  let resumeHandled = false;

  const handleInactive = () => {
    resumeHandled = false;
  };

  const handleResume = () => {
    if (!active || document.visibilityState === "hidden" || resumeHandled) return;
    resumeHandled = true;
    void refresh();
  };

  const handleVisibilityChange = () => {
    if (document.visibilityState === "hidden") {
      handleInactive();
      return;
    }
    handleResume();
  };

  onMounted(() => {
    window.addEventListener("focus", handleResume);
    window.addEventListener("blur", handleInactive);
    document.addEventListener("visibilitychange", handleVisibilityChange);
  });

  onBeforeUnmount(() => {
    active = false;
    window.removeEventListener("focus", handleResume);
    window.removeEventListener("blur", handleInactive);
    document.removeEventListener("visibilitychange", handleVisibilityChange);
  });

  return {
    isActive: () => active,
  };
}
