FROM centos:7

RUN yum -y clean all && yum -y update && yum -y install epel-release \
    && rpm --import http://li.nux.ro/download/nux/RPM-GPG-KEY-nux.ro \
    && rpm -Uvh http://li.nux.ro/download/nux/dextop/el7/x86_64/nux-dextop-release-0-5.el7.nux.noarch.rpm \
    && yum -y install ffmpeg python python-pip curl file ImageMagick && yum -y clean all && rm -rf /var/cache/yum
RUN pip install awscli
COPY scripts/extract_video_thumbnail.sh /usr/local/bin/extract_thumbnail.sh
RUN chmod a+x /usr/local/bin/extract_thumbnail.sh

USER daemon