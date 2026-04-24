/**
 * threadpool.h - 线程池接口
 *
 * 功能：声明线程池相关函数和数据结构。
 */

#ifndef THREADPOOL_H
#define THREADPOOL_H

#include <pthread.h>

typedef struct {
  void (*function)(void *);
  void *argument;
} ThreadPoolTask;

typedef struct {
  pthread_t *threads;
  ThreadPoolTask *queue;
  int queue_size;
  int queue_front;
  int queue_rear;
  int queue_count;
  int thread_count;
  int shutdown;
  pthread_mutex_t lock;
  pthread_cond_t notify;
} ThreadPool;

// 线程池管理
ThreadPool *threadpool_create(int thread_count, int queue_size);
int threadpool_add(ThreadPool *pool, void (*function)(void *), void *argument);
void threadpool_destroy(ThreadPool *pool);

#endif // THREADPOOL_H
