#include "keccak-gate.h"

#if defined(BC3_PORTABLE) || (!defined(KECCAK_8WAY) && !defined(KECCAK_4WAY))

#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "sph_keccak.h"
#include "../verthash/tiny_sha3/sha3.h"

void sha3d_hash(void *state, const void *input)
{
    uint8_t hash1[32];
    uint8_t hash2[32];

    sha3(input, 80, hash1, 32);
    sha3(hash1, 32, hash2, 32);
    sha3(hash2, 32, state, 32);
}

int scanhash_sha3d( struct work *work, uint32_t max_nonce,
                    uint64_t *hashes_done, struct thr_info *mythr )
{
   uint32_t _ALIGN(64) hash64[8];
   uint32_t _ALIGN(64) endiandata[32];
   uint32_t *pdata = work->data;
   uint32_t *ptarget = work->target;
	uint32_t n = pdata[19];
	const uint32_t first_nonce = pdata[19];
   const uint32_t last_nonce = max_nonce;
   const int thr_id = mythr->id;

   for ( int i=0; i < 19; i++ ) 
      be32enc( &endiandata[i], pdata[i] );

	do {
		be32enc( &endiandata[19], n ); 
		sha3d_hash( hash64, endiandata );
      if ( valid_hash( hash64, ptarget ) && !opt_benchmark )
      {
         pdata[19] = n;
         submit_solution( work, hash64, mythr );
		}
      n++;
   } while ( n < last_nonce && !work_restart[thr_id].restart );
	
	*hashes_done = n - first_nonce;
	pdata[19] = n;
	return 0;
}

#endif
