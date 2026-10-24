#include <linux/atomic.h>
#include <linux/errno.h>
#include <linux/ktime.h>
#include <linux/types.h>

#include "feature/online_root_lease.h"
#include "manager/manager_identity.h"
#include "policy/feature.h"
#include "klog.h" // IWYU pragma: keep

#define KSU_ONLINE_LEASE_MIN_SECONDS 15ULL
#define KSU_ONLINE_LEASE_MAX_SECONDS 300ULL

static atomic64_t online_root_lease_deadline = ATOMIC64_INIT(0);

bool ksu_online_root_lease_allows_current(void)
{
    u64 deadline;

    /* The certificate-verified Manager must always be able to renew a lease. */
    if (is_manager())
        return true;

    deadline = (u64)atomic64_read(&online_root_lease_deadline);
    return deadline > ktime_get_boottime_seconds();
}

static int online_root_lease_feature_get(u64 *value)
{
    u64 now = ktime_get_boottime_seconds();
    u64 deadline = (u64)atomic64_read(&online_root_lease_deadline);

    *value = deadline > now ? deadline - now : 0;
    return 0;
}

static int online_root_lease_feature_set(u64 value)
{
    u64 now;

    /* A root process must not be able to mint or extend its own online lease. */
    if (!is_manager())
        return -EPERM;

    if (value == 0) {
        atomic64_set(&online_root_lease_deadline, 0);
        return 0;
    }
    if (value < KSU_ONLINE_LEASE_MIN_SECONDS || value > KSU_ONLINE_LEASE_MAX_SECONDS)
        return -ERANGE;

    now = ktime_get_boottime_seconds();
    atomic64_set(&online_root_lease_deadline, now + value);
    pr_info("online_root_lease: armed for %llu seconds\n", value);
    return 0;
}

static const struct ksu_feature_handler online_root_lease_handler = {
    .feature_id = KSU_FEATURE_ONLINE_ROOT_LEASE,
    .name = "online_root_lease",
    .get_handler = online_root_lease_feature_get,
    .set_handler = online_root_lease_feature_set,
};

void __init ksu_online_root_lease_init(void)
{
    if (ksu_register_feature_handler(&online_root_lease_handler))
        pr_err("Failed to register online_root_lease feature handler\n");
}

void __exit ksu_online_root_lease_exit(void)
{
    atomic64_set(&online_root_lease_deadline, 0);
    ksu_unregister_feature_handler(KSU_FEATURE_ONLINE_ROOT_LEASE);
}
