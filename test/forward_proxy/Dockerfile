ARG UBUNTU_VERSION=22.04

FROM ubuntu:${UBUNTU_VERSION}

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && apt-get install -y apache2 apache2-dev libapache2-mod-xsendfile

RUN a2enmod expires
RUN a2enmod headers
RUN a2enmod proxy
RUN a2enmod proxy_http
RUN a2enmod proxy_connect
RUN a2enmod rewrite
RUN a2enmod ssl
RUN a2enmod xsendfile
RUN a2enmod cache
RUN a2enmod cache_disk
RUN a2enmod cache_socache
RUN a2enmod socache_shmcb
RUN a2enmod unique_id

COPY fw_proxy.conf /etc/apache2/sites-available/fw_proxy.conf

RUN a2ensite fw_proxy

RUN /usr/sbin/apache2ctl configtest
