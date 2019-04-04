import React from 'react';
import axios from 'axios';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import TimestampDiffComponent from "../common/TimestampDiffComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import ByCollectionChart from "./ByCollectionChart.jsx";
import GeneralOverviewChart from "./GeneralOverviewChart.jsx";

class ProxyHealthDetail extends React.Component {
    constructor(props) {
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            collectionSummary: [],
            mostRecentRun: null,
            previousRuns: [],
            selectedCollection: null
        };

        this.collectionSelectedCb = this.collectionSelectedCb.bind(this);
    }

    collectionSelectedCb(selected) {
        console.log("selected collection " + selected);
        this.setState({selectedCollection: selected});
    }

    loadData() {
        const loadingFuture = [
            axios.get("/api/proxyhealth/mostrecent"),
            axios.get("/api/proxyhealth/problemitems/collectionlist")
        ];

        this.setState({loading: true, lastError: null}, ()=>{
            axios.all(loadingFuture).then(responseList=>{
                this.setState({
                    loading: false,
                    lastError: null,
                    collectionSummary: responseList[1].data.entries,
                    mostRecentRun: responseList[0].data.entry
                })
            }).catch(err=>{
                console.error(err);
                this.setState({
                    loading: false,
                    lastError: err
                })
            })
        })
    }

    componentWillMount() {
        this.loadData();
    }

    render() {
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>

            <div className="chart-error-container">
                <ErrorViewComponent error={this.state.lastError}/>
            </div>

            <h3>Proxy Health</h3>
            {this.state.mostRecentRun ? <div className="info-box">Last check:
                    <ul>
                        <li>was at <TimestampFormatter relative={false} value={this.state.mostRecentRun.scanStart} formatString="hh:mm:ss a [on] MMMM Do YYYY"/>
                        </li>
                        <li>completed <TimestampDiffComponent startTime={this.state.mostRecentRun.scanStart}
                                                       endTime={this.state.mostRecentRun.scanFinish}/></li>
                    </ul>
            </div> : <div className="info-box"><p>Loading...</p></div>
            }

            <div className="chart-container">
                <GeneralOverviewChart recentCountData={this.state.mostRecentRun}/>
            </div>

            <div className="chart-container">
                <ByCollectionChart facetData={this.state.collectionSummary} dataSeriesClicked={this.collectionSelectedCb}/>
            </div>

        </div>
    }
}

export default ProxyHealthDetail;
