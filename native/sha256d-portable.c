#include <stddef.h>
#include "algo/sha/sph_sha2.h"

void sha256d(void *hash, const void *data, int len)
{
    sph_sha256_full(hash, data, (size_t)len);
    sph_sha256_full(hash, hash, 32);
}
