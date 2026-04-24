/**
 * threadpool.c - 线程池实现（优化精简版）
 *
 * 功能：管理一组工作线程，处理异步任务。
 */

#include "threadpool.h"
#include "debug.h"

#include <stdlib.h>
#include <string.h>

/* ---------------------------- 静态函数声明 ---------------------------- */
static void *threadpool_worker(void *threadpool);

/* ---------------------------- 函数实现 ---------------------------- */

/**
 * 创建线程池
 */
ThreadPool *threadpool_create(int thread_count, int queue_size) {
  if (thread_count <= 0 || queue_size <= 0) {
    ERROR("Invalid thread pool parameters: thread_count=%d, queue_size=%d",
          thread_count, queue_size);
    return NULL;
  }

  ThreadPool *pool = malloc(sizeof(ThreadPool));
  if (!pool) {
    ERROR("Failed to allocate thread pool");
    return NULL;
  }

  pool->threads = malloc(sizeof(pthread_t) * thread_count);
  pool->queue = malloc(sizeof(ThreadPoolTask) * queue_size);
  if (!pool->threads || !pool->queue) {
    ERROR("Failed to allocate thread array or task queue");
    free(pool->threads);
    free(pool->queue);
    free(pool);
    return NULL;
  }

  pool->thread_count = thread_count;
  pool->queue_size = queue_size;
  pool->queue_front = 0;
  pool->queue_rear = 0;
  pool->queue_count = 0;
  pool->shutdown = 0;

  if (pthread_mutex_init(&pool->lock, NULL) != 0 ||
      pthread_cond_init(&pool->notify, NULL) != 0) {
    ERROR("Failed to initialize mutex or condition variable");
    pthread_mutex_destroy(&pool->lock);
    pthread_cond_destroy(&pool->notify);
    free(pool->threads);
    free(pool->queue);
    free(pool);
    return NULL;
  }

  for (int i = 0; i < thread_count; i++) {
    if (pthread_create(&pool->threads[i], NULL, threadpool_worker, pool) != 0) {
      ERROR("Failed to create worker thread %d", i);
      threadpool_destroy(pool);
      return NULL;
    }
  }

  INFO("Thread pool created with %d threads and queue size %d", thread_count,
       queue_size);
  return pool;
}

/**
 * 添加任务到线程池
 */
int threadpool_add(ThreadPool *pool, void (*function)(void *), void *argument) {
  if (!pool || !function) {
    ERROR("Invalid parameters to threadpool_add");
    return -1;
  }

  pthread_mutex_lock(&pool->lock);

  if (pool->shutdown) {
    pthread_mutex_unlock(&pool->lock);
    ERROR("Cannot add task to shutdown thread pool");
    return -1;
  }

  if (pool->queue_count == pool->queue_size) {
    pthread_mutex_unlock(&pool->lock);
    WARN("Thread pool queue is full");
    return -1;
  }

  pool->queue[pool->queue_rear].function = function;
  pool->queue[pool->queue_rear].argument = argument;
  pool->queue_rear = (pool->queue_rear + 1) % pool->queue_size;
  pool->queue_count++;

  pthread_cond_signal(&pool->notify);
  pthread_mutex_unlock(&pool->lock);

  DBG("Task added, queue count: %d", pool->queue_count);
  return 0;
}

/**
 * 工作线程函数
 */
static void *threadpool_worker(void *threadpool) {
  ThreadPool *pool = threadpool;

  while (1) {
    pthread_mutex_lock(&pool->lock);

    while (pool->queue_count == 0 && !pool->shutdown)
      pthread_cond_wait(&pool->notify, &pool->lock);

    if (pool->shutdown) {
      pthread_mutex_unlock(&pool->lock);
      pthread_exit(NULL);
    }

    ThreadPoolTask task = pool->queue[pool->queue_front];
    pool->queue_front = (pool->queue_front + 1) % pool->queue_size;
    pool->queue_count--;

    pthread_mutex_unlock(&pool->lock);

    task.function(task.argument);
  }

  return NULL;
}

/**
 * 销毁线程池
 */
void threadpool_destroy(ThreadPool *pool) {
  if (!pool)
    return;

  pthread_mutex_lock(&pool->lock);
  pool->shutdown = 1;
  pthread_mutex_unlock(&pool->lock);

  pthread_cond_broadcast(&pool->notify);

  for (int i = 0; i < pool->thread_count; i++)
    pthread_join(pool->threads[i], NULL);

  free(pool->threads);
  free(pool->queue);
  pthread_mutex_destroy(&pool->lock);
  pthread_cond_destroy(&pool->notify);
  free(pool);

  INFO("Thread pool destroyed");
}
