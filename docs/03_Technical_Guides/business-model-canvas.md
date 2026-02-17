# Business Model Canvas

**A Portfolio Project by MapleExpectation Team**

## Overview

This document outlines the Business Model Canvas for MapleExpectation, a sophisticated Spring Boot application that calculates MapleStory equipment upgrade costs using Nexon's Open API. The project demonstrates enterprise-grade architecture principles and is designed for resilience and scalability.

## Mermaid Business Model Canvas

```mermaid
graph TD
    %% Key Partners
    subgraph "Key Partners"
        P1[Nexon API Partnership]
        P2[Cloud Infrastructure Provider<br/>(AWS/GCP)]
        P3[Payment Gateway Provider]
        P4[MapleStory Community Forums]
        P5[Open Source Contributors]
    end

    %% Key Activities
    subgraph "Key Activities"
        A1[API Integration & Maintenance]
        A2[Cost Algorithm Development]
        A3[Performance Optimization]
        A4[Security Compliance]
        A5[Community Management]
        A6[Feature Development]
    end

    %% Key Resources
    subgraph "Key Resources"
        R1[Spring Boot Architecture]
        R2[Resilience4j Framework]
        R3[MySQL Database]
        R4[Redis Cache]
        R5[Development Team]
        R6[Domain Knowledge]
    end

    %% Value Propositions
    subgraph "Value Propositions"
        VP1[Precise Cost Calculation]
        VP2[Real-time Market Data]
        VP3[High Performance<br/>1,000+ concurrent users]
        VP4[Cost-effective Infrastructure<br/>AWS t3.small]
        VP5[Free Access<br/>No Paywalls]
    end

    %% Customer Relationships
    subgraph "Customer Relationships"
        CR1[Community Forums]
        CR2[Discord Server]
        CR3[Regular Updates]
        CR4[Responsive Support]
        CR5[Feature Request System]
    end

    %% Customer Segments
    subgraph "Customer Segments"
        CS1[MapleStory Players<br/>(Casual & Hardcore)]
        CS2[Item Flippers]
        CS3[Guild Managers]
        CS4[Content Creators]
        CS5[MapleStory Theory Crafters]
    end

    %% Channels
    subgraph "Channels"
        CH1[Web Application]
        CH2[Mobile Responsive]
        CH3[API Access]
        CH4[Social Media]
        CH5[Gaming Communities]
        CH6[Search Engine]
    end

    %% Cost Structure
    subgraph "Cost Structure"
        C1[Server Hosting<br/>AWS t3.small]
        C2[Database Maintenance]
        C3[Developer Time]
        C4[API Rate Limits]
        C5[Monitoring & Observability]
        C6[Security Infrastructure]
    end

    %% Revenue Streams
    subgraph "Revenue Streams"
        R1[Donations<br/>Voluntary]
        R2[Patreon Support<br/>Optional]
        R3[API Access<br/>Future Potential]
        R4[Premium Features<br/>Future Potential]
        R5[Partnerships<br/>Future Potential]
    end

    %% Connections
    P1 --> A1
    P2 --> A3
    P3 --> A4
    P4 --> A5
    P5 --> A6

    A1 --> VP1
    A2 --> VP1
    A3 --> VP3
    A4 --> VP4
    A5 --> VP5
    A6 --> VP2

    VP1 --> CS1
    VP2 --> CS2
    VP3 --> CS3
    VP4 --> CS1
    VP5 --> CS4

    CS1 --> CH1
    CS2 --> CH1
    CS3 --> CH1
    CS4 --> CH4
    CS5 --> CH5

    CH1 --> CR1
    CH2 --> CR2
    CH3 --> CR3
    CH4 --> CR4
    CH5 --> CR5

    C1 -.-> R1
    C2 -.-> R1
    C3 -.-> R2
    C4 -.-> R1
    C5 -.-> R1
    C6 -.-> R1

    R1 --> A6
    R2 --> A6
    R3 --> A1
    R4 --> A6
    R5 --> A1

    R1 --> C3
    R2 --> C3
    R3 --> C3
    R4 --> C3
    R5 --> C3

    style P1 fill:#f9f,stroke:#333,stroke-width:2px
    style VP1 fill:#bbf,stroke:#333,stroke-width:2px
    style CS1 fill:#bfb,stroke:#333,stroke-width:2px
```

## Detailed BMC Elements

### 1. Key Partners

**Existing Partners:**
- **Nexon API Partnership**: Official API integration for real-time MapleStory data
- **Cloud Infrastructure Provider**: AWS/GCP for scalable hosting
- **Payment Gateway Provider**: Stripe/PayPal for donation processing (future)

**Additional Partners Added:**
- **MapleStory Community Forums**: Partnerships with popular MapleStory communities
- **Open Source Contributors**: Community contributors for feature enhancement
- **Gaming Influencers**: Content creators for promotion and feedback

### 2. Key Activities

**Existing Activities:**
- API integration and maintenance
- Cost algorithm development
- Performance optimization

**Enhanced Activities:**
- **Community Management**: Active engagement with player community
- **Feature Development**: Regular updates based on user feedback
- **Security Compliance**: Maintaining enterprise-grade security
- **Performance Monitoring**: Continuous optimization for 1,000+ concurrent users

### 3. Key Resources

**Existing Resources:**
- Spring Boot architecture
- Resilience4j framework
- MySQL database
- Redis cache
- Development team expertise

**Enhanced Resources:**
- **Domain Knowledge**: Deep Maple game mechanics understanding
- **Performance Infrastructure**: Optimized for low-cost, high-throughput scenarios
- **Monitoring Systems**: Comprehensive observability stack
- **Documentation**: Extensive technical documentation and API specs

### 4. Value Propositions

**Core Value Propositions:**
- **Precise Cost Calculation**: Accurate equipment upgrade cost calculations
- **Real-time Market Data**: Live item pricing and market trends
- **High Performance**: Handles 1,000+ concurrent users efficiently
- **Cost-effective Infrastructure**: Runs on low-cost AWS t3.small instances
- **Free Access**: No paywalls or hidden fees

**Unique Value Added:**
- **Transparency**: Open-source architecture with clear documentation
- **Reliability**: 99.9% uptime with resilience patterns
- **Community-driven**: Built by players, for players

### 5. Customer Relationships (NEW ELEMENT)

**Community Engagement:**
- **Community Forums**: Active participation in MapleStory forums
- **Discord Server**: Real-time support and community building
- **Regular Updates**: Feature updates based on community feedback
- **Responsive Support**: Quick response to user issues and questions
- **Feature Request System**: Structured process for user input

**Relationship Building:**
- **Content Creation**: Educational content about item enhancement
- **Player Guides**: Comprehensive guides for different playstyles
- **Event Coordination**: Special events and community challenges

### 6. Customer Segments

**Primary Segments:**
- **MapleStory Players**: Casual and hardcore players optimizing gear
- **Item Flippers**: Players trading for profit
- **Guild Managers**: Coordinating guild resources and investments
- **Content Creators**: Streamers and YouTubes creating MapleStory content
- **MapleStory Theory Crafters**: Deep dive into game mechanics

**Secondary Segments:**
- **New Players**: Learning about item enhancement mechanics
- **Returning Players**: Rediscovering optimal upgrade paths
- **Competitive Players**: Minimizing costs for maximum efficiency

### 7. Channels (NEW ELEMENT)

**Digital Channels:**
- **Web Application**: Primary platform at maple.expectation
- **Mobile Responsive**: Full functionality on mobile devices
- **API Access**: RESTful API for third-party integrations
- **Search Engine**: SEO optimized for relevant keywords

**Social & Community Channels:**
- **Social Media**: Twitter, Reddit, Discord communities
- **Gaming Communities**: MapleStory subreddit, forums, and platforms
- **GitHub**: Open-source repository for developers
- **Documentation**: Comprehensive API and user documentation

**Distribution Channels:**
- **Direct Access**: Primary web application
- **Community Distribution**: Shared through player networks
- **Content Marketing**: YouTube, Twitch, and gaming blogs

### 8. Cost Structure (Enhanced)

**Infrastructure Costs:**
- **Server Hosting**: AWS t3.small instances (~$20/month)
- **Database**: MySQL with replication and backup
- **Cache Layer**: Redis for performance optimization
- **Monitoring**: CloudWatch and Grafana stack

**Development Costs:**
- **Developer Time**: Part-time development team
- **API Rate Limits**: Nexon API usage optimization
- **Security Infrastructure**: SSL, DDoS protection, compliance
- **Community Management**: Moderation and support

**Operational Costs:**
- **Domain & Hosting**: Website and application hosting
- **Third-party Services**: Payment processing, analytics
- **Documentation**: Maintaining comprehensive docs
- **Community Tools**: Discord bot, forum integration

### 9. Revenue Streams (NEW ELEMENT)

**Current Revenue:**
- **Donations**: Voluntary player donations via PayPal
- **GitHub Sponsorship**: Developer support through GitHub

**Future Revenue Streams:**
- **Patreon Support**: Monthly subscriptions for exclusive features
- **API Access**: Premium API access for third-party applications
- **Premium Features**: Advanced analytics and historical data
- **Partnerships**: Sponsored content and promotions
- **Data Services**: Market intelligence for game developers

**Monetization Strategy:**
- **Freemium Model**: Core features free, premium features paid
- **Value-based Pricing**: Premium features based on utility
- **Community-first Approach**: Never paywall core functionality
- **Transparent Revenue**: Clear communication about fund usage

## Portfolio Project Context

This Business Model Canvas represents a portfolio project designed to demonstrate:

1. **Technical Excellence**: Enterprise-grade architecture with best practices
2. **Performance Optimization**: High throughput on low-cost infrastructure
3. **Community Focus**: Built for and by the MapleStory community
4. **Innovation**: Advanced patterns like LogicExecutor and Resilience4j
5. **Open Source Contribution**: Fully documented and accessible codebase

The project serves as a showcase for modern Java development, Spring Boot architecture, and scalable web applications handling significant real-world traffic.

---

*Last Updated: 2026-02-06*
*Document Version: 1.0*