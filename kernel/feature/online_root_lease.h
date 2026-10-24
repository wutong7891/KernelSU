#ifndef __KSU_H_ONLINE_ROOT_LEASE
#define __KSU_H_ONLINE_ROOT_LEASE

#include <linux/types.h>

bool ksu_online_root_lease_allows_current(void);
void ksu_online_root_lease_init(void);
void ksu_online_root_lease_exit(void);

#endif
