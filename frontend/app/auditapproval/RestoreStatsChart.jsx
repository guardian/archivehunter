import React from 'react';
import axios from 'axios';
import PropTypes from 'prop-types';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import LoadingThrobber from '../common/LoadingThrobber.jsx';
import {Bar, HorizontalBar} from 'react-chartjs-2';
import moment from 'moment';
import BytesFormatterImplementation from "../common/BytesFormatterImplementation.jsx";

class RestoreStatsChart extends React.Component {
    static propTypes = {
        graphCategory: PropTypes.string.isRequired,
        graphValues: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            chartData: null
        }
    }

    static makeColourValues(count, offset, alpha){
        let values = [];
        for(let n=0;n<count;++n){
            let hue = (n/count)*360.0 + offset;
            values[n] = 'hsla(' + hue + ',75%,50%,'+alpha+')';
        }
        return values;
    }

    static colourValues = RestoreStatsChart.makeColourValues(10,10, 0.6);
    static borderValues = RestoreStatsChart.makeColourValues(10,10,1.0);

    addColourValues(content){
        return Object.assign({}, content, {datasets: content.datasets.map((ds,ctr)=>Object.assign({},ds,{backgroundColor: RestoreStatsChart.colourValues[ctr], borderColor: RestoreStatsChart.borderValues[ctr]}))})
    }

    updateChart(){
        this.setState({loading: true}, ()=>axios.get("/api/audit/datastats?graphType=" + this.props.graphValues + "&graphSubLevel=" + this.props.graphCategory).then(response=>{
            this.setState({
                loading: false,
                chartData: this.addColourValues(response.data)
            });
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }));
    }

    componentWillMount() {
        this.updateChart();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        if(prevProps.graphCategory!==this.props.graphCategory) {console.log("graph category changed"); this.updateChart();}
        if(prevProps.graphValues!==this.props.graphValues) {console.log("graphValues changed"); this.updateChart();}
    }

    render(){
        console.log(this.state.chartData);
        return <div className="chart-container-wide">
            <LoadingThrobber show={this.state.loading} caption="Loading data..." large={true}/>
            {this.state.lastError ? <ErrorViewComponent error={this.state.lastError}/> : <p/>}
            {this.state.chartData ?
            <Bar
                data={this.state.chartData}
                height={700}
                width={1200}
                options={{
                    title: {
                        display: true,
                        text: "Data usage on "+ this.props.graphValues +"/"+this.props.graphCategory+" by month",
                        fontColor: "rgba(255,255,255,1)",
                        fontSize: 24
                    },
                    scales: {
                        yAxes: [{
                            gridLines: {
                                display: true,
                                color: "rgba(0,0,0,0.8)"
                            },
                            ticks: {
                                autoSkip: false,
                                fontColor: "rgba(255,255,255,1)",
                                callback: (value, index, values)=>{
                                    const result = BytesFormatterImplementation.getValueAndSuffix(value);
                                    return ""+result[0]+" " + result[1];
                                }
                            },
                            stacked: true
                        }],
                        xAxes: [{
                            ticks: {
                                fontColor: "rgba(255,255,255,1)",
                                callback: (value, index,values)=>moment(value).format("MMM YYYY")
                            },
                            stacked: true
                        }]
                    },
                    legend: {
                        display: true,
                        labels: {
                            fontColor: "rgba(255,255,255,1)"
                        },
                        position: "bottom"
                    }
                }}
                /> : <p/> }
        </div>
    }
}

export default RestoreStatsChart;