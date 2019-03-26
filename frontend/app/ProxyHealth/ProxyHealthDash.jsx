import React from 'react';
import axios from 'axios';
import {HorizontalBar} from 'react-chartjs-2';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";

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
            lastError: null
        }
    }

    embellish(data){
        return Object.assign({}, data, {
            datasets: data.datasets.map(dset=>Object.assign({}, dset, {backgroundColor: ProxyHealthDash.colourValues})),
        })
    }

    componentWillMount(){
        this.setState({loading: true}, ()=>axios.get("/api/search/proxyStats").then(response=>{
            const proxyBreakdownData = response.data.entries.filter(entry=>entry.name==="hasProxy");
            const typeBreakdownData = response.data.entries.filter(entry=>entry.name==="mediaType");

            this.setState({
                loading: false,
                proxyBreakdownData: proxyBreakdownData.length>0 ? this.embellish(proxyBreakdownData[0]) : {},
                typeBreakdownData: typeBreakdownData.length>0 ? this.embellish(typeBreakdownData[0]) : {}
             });
            console.log(ProxyHealthDash.colourValues);

            console.log(proxyBreakdownData.length>0 ? this.embellish(proxyBreakdownData[0]) : {});
        }))
    }
    render(){
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>

            <div className="chart-error-container">
                <ErrorViewComponent error={this.state.lastError}/>
            </div>

            <div className="chart-container-wide">
                <HorizontalBar
                    data={this.state.proxyBreakdownData}
                    height={400}
                    width={400}
                    options={{
                        maintainAspectRatio: false,
                        scales: {
                            yAxes: [{
                                type: "category",
                                gridLines: {
                                    display: true,
                                    color: "rgba(0,0,0,0.8)"
                                }
                            }]
                        }
                    }}
                />
            </div>
        </div>
    }
}

export default ProxyHealthDash;