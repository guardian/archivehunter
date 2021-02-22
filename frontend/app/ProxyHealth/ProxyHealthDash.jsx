import React from 'react';
import axios from 'axios';
import {HorizontalBar} from 'react-chartjs-2';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import AdminContainer from "../admin/AdminContainer";
import {Snackbar, createStyles, withStyles} from "@material-ui/core";
import MuiAlert from "@material-ui/lab/Alert";

const styles = createStyles({
    chartContainerWide: {
        float: "left",
        width: "50vw",
        position: "relative",
        overflow: "hidden"
    },
    chartContainer: {
        float: "left",
        width: "31vw",
        position: "relative"
    },
    listControlLabel: {
        marginBottom: 0
    }
});

class ProxyHealthDash extends React.Component
{
    static makeColourValues(count, offset){
        let values = [];
        for(let n=0;n<count;++n){
            let hue = (n/count)*360.0 + offset;
            values[n] = 'hsla(' + hue + ',75%,50%,0.6)'
        }
        return values;
    }

    static colourValues = ProxyHealthDash.makeColourValues(10,10);

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            proxyBreakdownData: {},
            typeBreakdownData: {},
            lastError: null,
            showingAlert: false
        }

        this.closeAlert = this.closeAlert.bind(this);
    }

    embellish(data){
        return Object.assign({}, data, {
            datasets: data.datasets.map((dset,idx)=>Object.assign({}, dset, {backgroundColor: ProxyHealthDash.colourValues[idx]})),
        })
    }

    componentDidMount(){
        this.setState({loading: true}, ()=>axios.get("/api/search/proxyStats")
            .then(response=>{
                const proxyBreakdownData = response.data.entries.filter(entry=>entry.name==="hasProxy");
                const typeBreakdownData = response.data.entries.filter(entry=>entry.name==="mediaType");

                this.setState({
                    loading: false,
                    proxyBreakdownData: proxyBreakdownData.length>0 ? this.embellish(proxyBreakdownData[0]) : {},
                    typeBreakdownData: typeBreakdownData.length>0 ? this.embellish(typeBreakdownData[0]) : {}
                 });
                console.log(ProxyHealthDash.colourValues);

                console.log(proxyBreakdownData.length>0 ? this.embellish(proxyBreakdownData[0]) : {});
            })
            .catch(err=>{
                console.error("could not load stats data: ", err);
                this.setState({
                    loading: false,
                    lastError: err,
                    showingAlert: true
                });
            })
        )
    }

    closeAlert() {
        this.setState({showingAlert: false});
    }

    render(){
        return <AdminContainer {...this.props}>
            <Snackbar autoHideDuration={8000} onClose={this.closeAlert} open={this.state.showingAlert}>
                <MuiAlert severity="error" onClose={this.closeAlert}>{ErrorViewComponent.formatError(this.state.lastError)}</MuiAlert>
            </Snackbar>

            <div className={this.props.classes.chartContainerWide}>
                <HorizontalBar
                    data={this.state.proxyBreakdownData}
                    height={400}
                    width={400}
                    options={{
                        title: {
                            display: true,
                            fontColor:"rgba(255,255,255,1)",
                            fontSize: 24,
                            text: "Proxied or Not?"
                        },
                        maintainAspectRatio: false,
                        scales: {
                            yAxes: [{
                                type: "category",
                                gridLines: {
                                    display: true,
                                    color: "rgba(0,0,0,0.8)"
                                },
                                ticks: {
                                    autoSkip: false,
                                    fontColor: "rgba(255,255,255,1)"
                                },
                                stacked: true
                            }],
                            xAxes: [{
                                stacked: true,
                            }]
                        },
                        legend: {
                            labels: {
                                fontColor: "rgba(255,255,255,1)"
                            },
                            position: "bottom"
                        }
                    }}
                />
            </div>
            <div className={this.props.classes.chartContainer}>
                <HorizontalBar
                    data={this.state.typeBreakdownData}
                    height={400}
                    width={200}
                    options={{
                        title: {
                            display: true,
                            text: "Media Types by Collection",
                            fontColor:"rgba(255,255,255,1)",
                            fontSize: 24
                        },
                        maintainAspectRatio: false,
                        scales: {
                            yAxes: [{
                                type: "category",
                                gridLines: {
                                    display: true,
                                    color: "rgba(0,0,0,0.8)"
                                },
                                ticks: {
                                    autoSkip: false,
                                    fontColor: "rgba(255,255,255,1)"
                                },
                                stacked: true
                            }],
                            xAxes: [{
                                stacked: true
                            }]
                        },
                        legend: {
                            labels: {
                                fontColor: "rgba(255,255,255,1)"
                            },
                            position: "bottom"
                        }
                    }}
                />
            </div>
        </AdminContainer>
    }
}

export default withStyles(styles)(ProxyHealthDash);