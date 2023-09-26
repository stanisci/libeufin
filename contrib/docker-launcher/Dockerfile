FROM debian:stable

RUN apt-get update
RUN apt-get install -y openjdk-17-jre git curl postgresql python3-pip

# Installation
RUN git clone git://git.taler.net/libeufin
WORKDIR /libeufin
RUN ./bootstrap
RUN ./configure --prefix=/usr/local
RUN make install
WORKDIR /
COPY launch-bank.sh /launch-bank.sh
RUN apt-get install -y sudo
RUN grep -v ^host.*all /etc/postgresql/15/main/pg_hba.conf > /tmp/pg_hba_buf.txt
RUN echo "host libeufincheck all 127.0.0.1/32 trust" >> /tmp/pg_hba_buf.txt
RUN echo "host libeufincheck all ::1/128 trust" >> /tmp/pg_hba_buf.txt
RUN cp /tmp/pg_hba_buf.txt /etc/postgresql/15/main/pg_hba.conf

ENTRYPOINT ["/launch-bank.sh"]
