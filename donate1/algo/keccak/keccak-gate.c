#include "keccak-gate.h"
#include "sph_keccak.h"
#include "algo/sha/sha256d.h"
#include "../verthash/tiny_sha3/sha3.h"

int hard_coded_eb = 1;

#ifdef BC3_PORTABLE
void sha3d_hash(void *state, const void *input);
int scanhash_sha3d(struct work *work, uint32_t max_nonce,
                   uint64_t *hashes_done, struct thr_info *mythr);
#endif

// KECCAK

bool register_keccak_algo( algo_gate_t* gate )
{
  gate->optimizations = AVX2_OPT | AVX512_OPT;
  gate->gen_merkle_root = (void*)&sha256_gen_merkle_root;
  opt_target_factor = 128.0;
#if defined (KECCAK_8WAY)
  gate->scanhash  = (void*)&scanhash_keccak_8way;
  gate->hash      = (void*)&keccakhash_8way;
#elif defined (KECCAK_4WAY)
  gate->scanhash  = (void*)&scanhash_keccak_4way;
  gate->hash      = (void*)&keccakhash_4way;
#elif defined (KECCAK_2WAY)
  gate->scanhash  = (void*)&scanhash_keccak_2x64;
  gate->hash      = (void*)&keccakhash_2x64;
#else
  gate->scanhash  = (void*)&scanhash_keccak;
  gate->hash      = (void*)&keccakhash;
#endif
  return true;
};

// KECCAKC

bool register_keccakc_algo( algo_gate_t* gate )
{
  gate->optimizations = AVX2_OPT | AVX512_OPT;
  gate->gen_merkle_root = (void*)&sha256d_gen_merkle_root;
  opt_target_factor = 256.0;
#if defined (KECCAK_8WAY)
  gate->scanhash  = (void*)&scanhash_keccak_8way;
  gate->hash      = (void*)&keccakhash_8way;
#elif defined (KECCAK_4WAY)
  gate->scanhash  = (void*)&scanhash_keccak_4way;
  gate->hash      = (void*)&keccakhash_4way;
#elif defined (KECCAK_2WAY)
  gate->scanhash  = (void*)&scanhash_keccak_2x64;
  gate->hash      = (void*)&keccakhash_2x64;
#else
  gate->scanhash  = (void*)&scanhash_keccak;
  gate->hash      = (void*)&keccakhash;
#endif
  return true;
};

// SHA3D

void sha3d( void *state, const void *input, int len )
{
   uint8_t hash1[32];
   uint8_t hash2[32];

   sha3(input, len, hash1, 32);
   sha3(hash1, 32, hash2, 32);
   sha3(hash2, 32, state, 32);
}

void sha3d_gen_merkle_root( char* merkle_root, struct stratum_ctx* sctx )
{
  sha3d( merkle_root, sctx->job.coinbase, (int) sctx->job.coinbase_size );
  for ( int i = 0; i < sctx->job.merkle_count; i++ )
  {
     memcpy( merkle_root + 32, sctx->job.merkle[i], 32 );
     sha256d( merkle_root, merkle_root, 64 );
  }
}

bool register_sha3d_algo( algo_gate_t* gate )
{
  hard_coded_eb = 6;
  gate->optimizations = SSE2_OPT | AVX2_OPT | AVX512_OPT | NEON_OPT;
  gate->gen_merkle_root = (void*)&sha256d_gen_merkle_root;
#if defined (BC3_PORTABLE)
  gate->scanhash  = (void*)&scanhash_sha3d;
  gate->hash      = (void*)&sha3d_hash;
#elif defined (SHA3D_8WAY)
  gate->scanhash  = (void*)&scanhash_sha3d_8way;
  gate->hash      = (void*)&sha3d_hash_8way;
#elif defined (SHA3D_4WAY)
  gate->scanhash  = (void*)&scanhash_sha3d_4way;
  gate->hash      = (void*)&sha3d_hash_4way;
#elif defined (SHA3D_2WAY)
  gate->scanhash  = (void*)&scanhash_sha3d_2x64;
  gate->hash      = (void*)&sha3d_hash_2x64;
#else
  gate->scanhash  = (void*)&scanhash_sha3d;
  gate->hash      = (void*)&sha3d_hash;
#endif
  return true;
};
