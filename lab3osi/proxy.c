#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <time.h>
#include <errno.h>

#define PORT 80
#define BUFFER_SIZE 4096
#define MAX_CACHE_SIZE 50
#define MAX_RESPONSE_SIZE (100 * 1024 * 1024)  // 100 MB
#define MAX_CLIENTS 50
#define CACHE_TTL 300

typedef struct {
    char *data;
    size_t size;
    size_t capacity;
    int complete;
    int error;
    pthread_mutex_t lock;
    pthread_cond_t data_available;
    int readers;
} stream_buffer_t;

typedef struct cache_entry {
    char *url;
    stream_buffer_t *buffer;
    time_t timestamp;
    struct cache_entry *prev;
    struct cache_entry *next;
} cache_entry_t;

typedef struct {
    cache_entry_t *head;
    cache_entry_t *tail;
    size_t count;
    pthread_mutex_t lock;
} cache_t;

cache_t global_cache = {NULL, NULL, 0, PTHREAD_MUTEX_INITIALIZER};

int parse_http_request(const char *request, char *host, char *path, int *port) {
    const char *start = strstr(request, "GET ");
    if (!start) return -1;
    
    start += 4;
    const char *end = strstr(start, " HTTP/");
    if (!end) return -1;
    
    char url[2048];
    size_t url_len = end - start;
    if (url_len >= sizeof(url)) return -1;
    
    strncpy(url, start, url_len);
    url[url_len] = '\0';
    
    const char *url_start = url;
    if (strncmp(url, "http://", 7) == 0) {
        url_start += 7;
    }
    
    const char *path_start = strchr(url_start, '/');
    if (path_start) {
        strcpy(path, path_start);
        size_t host_len = path_start - url_start;
        strncpy(host, url_start, host_len);
        host[host_len] = '\0';
    } else {
        strcpy(path, "/");
        strcpy(host, url_start);
    }
    
    char *port_sep = strchr(host, ':');
    if (port_sep) {
        *port = atoi(port_sep + 1);
        *port_sep = '\0';
    } else {
        *port = 80;
    }
    
    return 0;
}

stream_buffer_t* create_stream_buffer() {
    stream_buffer_t *buf = malloc(sizeof(stream_buffer_t));
    if (!buf) return NULL;
    
    buf->capacity = BUFFER_SIZE * 4;
    buf->data = malloc(buf->capacity);
    if (!buf->data) {
        free(buf);
        return NULL;
    }
    
    buf->size = 0;
    buf->complete = 0;
    buf->error = 0;
    buf->readers = 0;
    pthread_mutex_init(&buf->lock, NULL);
    pthread_cond_init(&buf->data_available, NULL);
    
    return buf;
}

int stream_buffer_append(stream_buffer_t *buf, const char *data, size_t len) {
    pthread_mutex_lock(&buf->lock);
    
    if (buf->size + len > MAX_RESPONSE_SIZE) {
        printf("[ERROR] Превышен MAX_RESPONSE_SIZE\n");
        buf->error = 1;
        pthread_cond_broadcast(&buf->data_available);
        pthread_mutex_unlock(&buf->lock);
        return -1;
    }
    
    if (buf->size + len > buf->capacity) {
        size_t new_capacity = buf->capacity * 2;
        while (new_capacity < buf->size + len) new_capacity *= 2;
        
        char *new_data = realloc(buf->data, new_capacity);
        if (!new_data) {
            printf("[ERROR] realloc failed\n");
            buf->error = 1;
            pthread_cond_broadcast(&buf->data_available);
            pthread_mutex_unlock(&buf->lock);
            return -1;
        }
        
        buf->data = new_data;
        buf->capacity = new_capacity;
    }
    
    memcpy(buf->data + buf->size, data, len);
    buf->size += len;
    
    pthread_cond_broadcast(&buf->data_available);
    pthread_mutex_unlock(&buf->lock);
    return 0;
}

void stream_buffer_complete(stream_buffer_t *buf) {
    pthread_mutex_lock(&buf->lock);
    buf->complete = 1;
    pthread_cond_broadcast(&buf->data_available);
    pthread_mutex_unlock(&buf->lock);
}

void free_stream_buffer(stream_buffer_t *buf) {
    if (!buf) return;
    pthread_mutex_destroy(&buf->lock);
    pthread_cond_destroy(&buf->data_available);
    free(buf->data);
    free(buf);
}

cache_entry_t* cache_find(const char *url) {
    pthread_mutex_lock(&global_cache.lock);
    
    cache_entry_t *entry = global_cache.head;
    time_t now = time(NULL);
    
    while (entry) {
        if (strcmp(entry->url, url) == 0) {
            if (now - entry->timestamp > CACHE_TTL) {
                pthread_mutex_unlock(&global_cache.lock);
                return NULL;
            }
            
            if (entry != global_cache.tail) {
                if (entry->prev) entry->prev->next = entry->next;
                if (entry->next) entry->next->prev = entry->prev;
                if (entry == global_cache.head) global_cache.head = entry->next;
                
                entry->prev = global_cache.tail;
                entry->next = NULL;
                if (global_cache.tail) global_cache.tail->next = entry;
                global_cache.tail = entry;
                if (!global_cache.head) global_cache.head = entry;
            }
            
            pthread_mutex_unlock(&global_cache.lock);
            return entry;
        }
        entry = entry->next;
    }
    
    pthread_mutex_unlock(&global_cache.lock);
    return NULL;
}

cache_entry_t* cache_add(const char *url, stream_buffer_t *buffer) {
    pthread_mutex_lock(&global_cache.lock);
    
    while (global_cache.count >= MAX_CACHE_SIZE && global_cache.head) {
        cache_entry_t *old = global_cache.head;
        global_cache.head = old->next;
        if (global_cache.head) {
            global_cache.head->prev = NULL;
        } else {
            global_cache.tail = NULL;
        }
        
        pthread_mutex_lock(&old->buffer->lock);
        while (old->buffer->readers > 0) {
            pthread_mutex_unlock(&old->buffer->lock);
            usleep(10000);
            pthread_mutex_lock(&old->buffer->lock);
        }
        pthread_mutex_unlock(&old->buffer->lock);
        
        free_stream_buffer(old->buffer);
        free(old->url);
        free(old);
        global_cache.count--;
    }
    
    cache_entry_t *entry = malloc(sizeof(cache_entry_t));
    if (!entry) {
        pthread_mutex_unlock(&global_cache.lock);
        return NULL;
    }
    
    entry->url = strdup(url);
    entry->buffer = buffer;
    entry->timestamp = time(NULL);
    entry->prev = global_cache.tail;
    entry->next = NULL;
    
    if (global_cache.tail) {
        global_cache.tail->next = entry;
    } else {
        global_cache.head = entry;
    }
    global_cache.tail = entry;
    global_cache.count++;
    
    pthread_mutex_unlock(&global_cache.lock);
    return entry;
}

void stream_to_client(int client_socket, stream_buffer_t *buf) {
    pthread_mutex_lock(&buf->lock);
    buf->readers++;
    
    size_t sent = 0;
    
    while (1) {
        while (sent >= buf->size && !buf->complete && !buf->error) {
            pthread_cond_wait(&buf->data_available, &buf->lock);
        }
        
        if (buf->error) {
            buf->readers--;
            pthread_mutex_unlock(&buf->lock);
            return;
        }
        
        while (sent < buf->size) {
            size_t to_send = buf->size - sent;
            pthread_mutex_unlock(&buf->lock);
            
            ssize_t n = write(client_socket, buf->data + sent, to_send);
            
            pthread_mutex_lock(&buf->lock);
            
            if (n <= 0) {
                buf->readers--;
                pthread_mutex_unlock(&buf->lock);
                return;
            }
            
            sent += n;
        }
        
        if (buf->complete && sent >= buf->size) {
            break;
        }
    }
    
    buf->readers--;
    pthread_mutex_unlock(&buf->lock);
}

typedef struct {
    char *url;
    char *host;
    char *path;
    int port;
    stream_buffer_t *buffer;
} download_args_t;

void* download_thread(void *arg) {
    download_args_t *args = (download_args_t*)arg;
    
    printf("[DOWNLOAD] Загрузка: %s\n", args->url);
    
    struct hostent *server = gethostbyname(args->host);
    if (!server) {
        printf("[ERROR] gethostbyname: %s\n", args->host);
        args->buffer->error = 1;
        stream_buffer_complete(args->buffer);
        free(args->url);
        free(args->host);
        free(args->path);
        free(args);
        return NULL;
    }
    
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        printf("[ERROR] socket\n");
        args->buffer->error = 1;
        stream_buffer_complete(args->buffer);
        free(args->url);
        free(args->host);
        free(args->path);
        free(args);
        return NULL;
    }
    
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(args->port);
    memcpy(&server_addr.sin_addr.s_addr, server->h_addr, server->h_length);
    
    if (connect(sock, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        printf("[ERROR] connect\n");
        args->buffer->error = 1;
        stream_buffer_complete(args->buffer);
        close(sock);
        free(args->url);
        free(args->host);
        free(args->path);
        free(args);
        return NULL;
    }
    
    char request[2048];
    snprintf(request, sizeof(request),
             "GET %s HTTP/1.0\r\n"
             "Host: %s\r\n"
             "Connection: close\r\n"
             "\r\n",
             args->path, args->host);
    
    write(sock, request, strlen(request));
    
    char buffer[BUFFER_SIZE];
    ssize_t n;
    size_t total = 0;
    
    while ((n = read(sock, buffer, BUFFER_SIZE)) > 0) {
        total += n;
        if (stream_buffer_append(args->buffer, buffer, n) < 0) {
            printf("[ERROR] append на %zu байт\n", total);
            break;
        }
    }
    
    printf("[DOWNLOAD] Завершено: %.2f MB\n", total / (1024.0*1024.0));
    stream_buffer_complete(args->buffer);
    close(sock);
    
    free(args->url);
    free(args->host);
    free(args->path);
    free(args);
    
    return NULL;
}

void* handle_client(void *arg) {
    int client_socket = *(int*)arg;
    free(arg);
    
    char request[8192];
    size_t total = 0;
    ssize_t n;
    
    while (total < sizeof(request) - 1 && 
           (n = read(client_socket, request + total, sizeof(request) - 1 - total)) > 0) {
        total += n;
        if (strstr(request, "\r\n\r\n")) break;
    }
    
    if (total == 0) {
        close(client_socket);
        return NULL;
    }
    request[total] = '\0';
    
    printf("[REQUEST] %.*s\n", (int)strcspn(request, "\r\n"), request);
    
    char host[256], path[1024], url[2048];
    int port;
    
    if (parse_http_request(request, host, path, &port) < 0) {
        const char *error = "HTTP/1.0 400 Bad Request\r\n\r\n";
        write(client_socket, error, strlen(error));
        close(client_socket);
        return NULL;
    }
    
    snprintf(url, sizeof(url), "%s:%d%s", host, port, path);
    
    cache_entry_t *cached = cache_find(url);
    
    if (cached && cached->buffer->complete && !cached->buffer->error) {
        printf("[CACHE HIT готово] %s (%.1f MB)\n", url, cached->buffer->size / (1024.0*1024));
        pthread_mutex_lock(&cached->buffer->lock);
        ssize_t sent = 0;
        while (sent < cached->buffer->size) {
            ssize_t n = write(client_socket, cached->buffer->data + sent, cached->buffer->size - sent);
            if (n <= 0) break;
            sent += n;
        }
        pthread_mutex_unlock(&cached->buffer->lock);
        close(client_socket);
        return NULL;
    }
    
    if (cached && !cached->buffer->complete) {
        printf("[CACHE HIT streaming] %s\n", url);
        stream_to_client(client_socket, cached->buffer);
        close(client_socket);
        return NULL;
    }
    
    printf("[CACHE MISS] %s\n", url);
    stream_buffer_t *buffer = create_stream_buffer();
    if (!buffer) {
        const char *error = "HTTP/1.0 500 Internal Server Error\r\n\r\n";
        write(client_socket, error, strlen(error));
        close(client_socket);
        return NULL;
    }
    
    cache_entry_t *entry = cache_add(url, buffer);
    if (!entry) {
        free_stream_buffer(buffer);
        const char *error = "HTTP/1.0 500 Internal Server Error\r\n\r\n";
        write(client_socket, error, strlen(error));
        close(client_socket);
        return NULL;
    }

    download_args_t *dl_args = malloc(sizeof(download_args_t));
    if (!dl_args) {
        free_stream_buffer(buffer);
        close(client_socket);
        return NULL;
    }
    
    dl_args->url = strdup(url);
    dl_args->host = strdup(host);
    dl_args->path = strdup(path);
    dl_args->port = port;
    dl_args->buffer = buffer;
    
    pthread_t dl_thread;
    if (pthread_create(&dl_thread, NULL, download_thread, dl_args) != 0) {
        printf("[ERROR] pthread_create download\n");
        free(dl_args->url);
        free(dl_args->host);
        free(dl_args->path);
        free(dl_args);
        free_stream_buffer(buffer);
        close(client_socket);
        return NULL;
    }
    pthread_detach(dl_thread);
   
    stream_to_client(client_socket, buffer);
    close(client_socket);
    
    return NULL;
}


int main() {
    int server_socket = socket(AF_INET, SOCK_STREAM, 0);
    if (server_socket < 0) {
        perror("socket");
        return 1;
    }
    
    int reuse = 1;
    setsockopt(server_socket, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(PORT);
    
    if (bind(server_socket, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        perror("bind");
        close(server_socket);
        return 1;
    }
    
    if (listen(server_socket, MAX_CLIENTS) < 0) {
        perror("listen");
        close(server_socket);
        return 1;
    }
    
    printf("HTTP Proxy запущен на порту %d\n", PORT);
    
    while (1) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        
        int client_socket = accept(server_socket, (struct sockaddr*)&client_addr, &client_len);
        if (client_socket < 0) {
            continue;
        }
        
        int *client_sock_ptr = malloc(sizeof(int));
        *client_sock_ptr = client_socket;
        
        pthread_t thread;
        pthread_create(&thread, NULL, handle_client, client_sock_ptr);
        pthread_detach(thread);
    }
    
    close(server_socket);
    return 0;
}
