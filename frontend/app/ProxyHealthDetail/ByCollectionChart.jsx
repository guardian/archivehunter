import React from 'react';
import PropTypes from 'prop-types';
import {HorizontalBar} from 'react-chartjs-2';

class ByCollectionChart extends React.Component {
    static propTypes = {
        facetData: PropTypes.array.isRequired,
        dataSeriesClicked: PropTypes.func
    };

    static makeColourValues(count, offset){
        let values = [];
        for(let n=0;n<count;++n){
            let hue = (n/count)*360.0 + offset;
            values[n] = 'hsla(' + hue + ',75%,50%,0.6)'
        }
        return values;
    }

    static colourValues = ByCollectionChart.makeColourValues(10,10);

    constructor(props){
        super(props);

        this.state = {
            chartData: null,
            dataSetNames: []
        }
    }

    componentWillMount() {
        this.updateChartData();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        if(prevProps.facetData!==this.props.facetData) this.updateChartData();
    }

    /**
     * updates the ChartJS format of data from the server-format data provided in props
     */
    updateChartData(){
        if(this.props.facetData) {
            this.setState({
                dataSetNames: this.props.facetData.map(item => item.key),
                chartData: {
                    labels: this.props.facetData.map(item => item.key),
                    datasets: [{
                        label: "Problem items",
                        data: this.props.facetData.map(item => item.count),
                        backgroundColor: ByCollectionChart.colourValues
                    }],

                }
            });
        }
    }

    render(){
        return <HorizontalBar
            data={this.state.chartData}
            height={400}
            width={200}
            getElementsAtEvent={elems=>{
                console.log(elems);
                if(this.props.dataSeriesClicked){
                    const clickedName = this.state.dataSetNames[elems[0]._index];
                    console.log("Clicked " + clickedName);
                    this.props.dataSeriesClicked(clickedName);
                }
            }}
            options={{
                title: {
                    display: true,
                    text: "Problems by collection",
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
                    display: false,
                    labels: {
                        fontColor: "rgba(255,255,255,1)"
                    },
                    position: "bottom"
                }
            }}
        />
    }
}

export default ByCollectionChart;